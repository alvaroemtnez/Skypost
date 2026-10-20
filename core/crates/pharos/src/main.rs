//! `pharos` — the Skypost base station: decode every lane, ACK, store and
//! forward, register field users, and hand messages to the bridges.
//!
//! Status: skeleton. The station logic (`station.rs`), registry and queue are
//! real and tested; the radio side is a loopback until the JS8 engine is wired
//! in (phase 1), and the Signal/WhatsApp/MeshCore bridges are TODO (the Phase 0
//! Python prototype covers Signal meanwhile).

// Skeleton: config fields and station effects are wired up once the PHY task
// lands (phase 1). Tests exercise them already.
#![allow(dead_code)]

mod access;
mod admin;
mod config;
mod hub;
mod outbox;
mod registry;
mod router;
mod station;

use access::Who;
use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};
use config::{Config, RadioKind};
use keryx::access::AccessKey;
use keryx::admin::{Switch, how};
use keryx::bridge::Address;
use keryx::uri::{SkypostUri, valid_alias, valid_callsign};
use keryx::{Identity, Keyring, PeerKind};
use station::{Effect, State, Station, StationCfg};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tracing::{info, warn};

#[derive(Parser)]
#[command(name = "pharos", version, about = "Pharos, the Skypost base station")]
struct Args {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Create this Pharos's identity and state file.
    Init {
        #[arg(long)]
        state: PathBuf,
        #[arg(long)]
        alias: String,
        #[arg(long)]
        callsign: String,
    },
    /// Print the skypost: URI to show as a QR code to field users.
    Identity {
        #[arg(long)]
        state: PathBuf,
    },
    /// Run the station.
    Run {
        #[arg(long, default_value = "base.toml")]
        config: PathBuf,
    },
    /// Switches, admins and pending requests in the state file.
    Status {
        #[arg(long)]
        state: PathBuf,
    },
    /// Set a switch (open, requests, login, emergency, stealth: on|off) or
    /// request-ttl-hours. Stop the service first.
    Set {
        #[arg(long)]
        state: PathBuf,
        name: String,
        value: String,
    },
    /// Add or remove an admin. Stop the service first.
    Admin {
        #[arg(long)]
        state: PathBuf,
        #[command(subcommand)]
        action: AdminAction,
    },
}

#[derive(Subcommand)]
enum AdminAction {
    /// WHO: @alias, a skypost:id/ URI, signal:+34…, signal:user.01,
    /// whatsapp:+34… or meshcore:<64 hex>.
    Add {
        who: String,
    },
    Remove {
        who: String,
    },
}

/// Parse an admin for the CLI and the config.
fn parse_who(state: &State, s: &str) -> Result<Who> {
    let s = s.trim();
    if let Some(alias) = s.strip_prefix('@') {
        return state
            .registry
            .find(alias)
            .map(Who::Radio)
            .with_context(|| format!("no field user @{alias}"));
    }
    if s.starts_with("skypost:") {
        return match SkypostUri::parse(s)? {
            SkypostUri::Id(card) => Ok(Who::Radio(card.key)),
            SkypostUri::Group { .. } => bail!("a group is not an admin"),
        };
    }
    let (net, rest) = s.split_once(':').unwrap_or(("signal", s));
    let phone = || Address::normalize_phone(rest);
    Ok(Who::Contact(match net {
        "signal" if rest.contains('.') => Address::SignalUsername(rest.to_owned()),
        "signal" => Address::SignalPhone(phone()),
        "whatsapp" => Address::WhatsappPhone(phone()),
        "meshcore" => {
            let b = data_encoding::HEXLOWER_PERMISSIVE
                .decode(rest.as_bytes())
                .context("meshcore key: 64 hex digits")?;
            Address::MeshcoreContact(
                b.try_into()
                    .map_err(|_| anyhow::anyhow!("meshcore key: 32 bytes"))?,
            )
        }
        other => bail!("unknown network {other}"),
    }))
}

fn switch_by_name(name: &str) -> Option<Switch> {
    Switch::ALL
        .into_iter()
        .find(|s| format!("{s:?}").eq_ignore_ascii_case(name))
}

/// First run: seed switches, admins, the allow list and authorised contacts
/// from `base.toml`. Later runs warn that those keys are ignored.
fn import(cfg: &Config, station: &mut Station) -> Result<()> {
    if station.state().imported {
        if cfg.access.has_seeds() || !cfg.bot.admins.is_empty() || !cfg.clients.is_empty() {
            warn!(
                "base.toml access settings, admins and clients are only read on the first run; they now live in the state file (pharos set / pharos admin)"
            );
        }
        return Ok(());
    }
    let a = &cfg.access;
    for (s, v) in [
        (Switch::Open, a.open),
        (Switch::Requests, a.requests),
        (Switch::Login, a.login),
        (Switch::Stealth, a.stealth),
    ] {
        if let Some(v) = v {
            station.set_switch(s, v);
        }
    }
    if let Some(h) = a.request_ttl_hours {
        station.access_mut().request_ttl_secs = h * 3600;
    }
    for c in &cfg.clients {
        let SkypostUri::Id(card) = SkypostUri::parse(&c.key)? else {
            bail!("[[clients]] key must be a skypost:id/ URI");
        };
        let key = card.key;
        station.allow(card)?;
        if c.admin {
            station.access_mut().add_admin(Who::Radio(key), how::SSH);
        }
        for contact in &c.contacts {
            if let Who::Contact(addr) = parse_who(station.state(), contact)? {
                station.state_mut().bot.authorized.insert(addr);
            }
        }
    }
    for admin in &cfg.bot.admins {
        let w = parse_who(station.state(), admin)?;
        if let Who::Contact(addr) = &w {
            station.state_mut().bot.authorized.insert(addr.clone());
        }
        station.access_mut().add_admin(w, how::SSH);
    }
    station.state_mut().imported = true;
    Ok(())
}

fn load_state(path: &Path) -> Result<State> {
    let raw = std::fs::read_to_string(path)
        .with_context(|| format!("reading state {}", path.display()))?;
    Ok(serde_json::from_str(&raw)?)
}

/// Atomic write, owner-only permissions: the state holds key material.
fn save_state(path: &Path, state: &State) -> Result<()> {
    let tmp = path.with_extension("tmp");
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut f = opts.open(&tmp)?;
    f.write_all(serde_json::to_string(state)?.as_bytes())?;
    f.sync_all()?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

fn deliver(e: &Effect) {
    match e {
        Effect::BridgeOut {
            alias, to, message, ..
        } => {
            info!(%alias, to = %to.display(), "{}", message.render());
        }
        Effect::Sos { alias, message, .. } => {
            warn!(%alias, "SOS — alert every bridge: {}", message.render())
        }
        Effect::Inbox { alias, .. } => info!(%alias, "message to the Pharos"),
        Effect::Log(l) => info!("{l}"),
        _ => {}
    }
    // TODO(phase 1): Signal, WhatsApp and MeshCore sidecars and the bot
    // router (decisions 0014, 0016).
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    match Args::parse().cmd {
        Cmd::Init {
            state,
            alias,
            callsign,
        } => {
            if state.exists() {
                bail!(
                    "{} exists; a new identity breaks every client's trust",
                    state.display()
                );
            }
            let callsign = callsign.to_ascii_uppercase();
            if !valid_alias(&alias) || !valid_callsign(&callsign) {
                bail!("alias: 1-16 letters/digits/_-. ; callsign: e.g. EA5YYY");
            }
            let k = Keyring::new(
                Identity::generate()?,
                &alias,
                Some(&callsign),
                PeerKind::Pharos,
            );
            let s = State::new(k);
            save_state(&state, &s)?;
            println!("{}", SkypostUri::Id(s.keyring.card().clone()).format());
        }
        Cmd::Identity { state } => {
            let s = load_state(&state)?;
            println!("{}", SkypostUri::Id(s.keyring.card().clone()).format());
            eprintln!("safety number: {}", s.keyring.public().safety_number());
        }
        Cmd::Run { config } => run(&config).await?,
        Cmd::Status { state } => {
            let s = load_state(&state)?;
            for sw in Switch::ALL {
                println!("{sw:?}: {}", if s.access.on(sw) { "on" } else { "off" });
            }
            println!("request TTL: {} h", s.access.request_ttl_secs / 3600);
            for a in &s.access.admins {
                println!("admin: {:?} (how {})", a.who, a.how);
            }
            for r in &s.access.requests {
                println!("request #{}: {} ({:?})", r.num, r.name, r.who);
            }
        }
        Cmd::Set { state, name, value } => {
            let mut s = load_state(&state)?;
            if name == "request-ttl-hours" {
                s.access.request_ttl_secs = value.parse::<u64>()? * 3600;
            } else {
                let sw = switch_by_name(&name).with_context(|| format!("unknown switch {name}"))?;
                let on = match value.as_str() {
                    "on" => true,
                    "off" => false,
                    _ => bail!("on or off"),
                };
                let mut st = Station::new(s, StationCfg::default());
                st.set_switch(sw, on);
                s = st.state().clone();
            }
            save_state(&state, &s)?;
        }
        Cmd::Admin { state, action } => {
            let mut s = load_state(&state)?;
            match action {
                AdminAction::Add { who } => {
                    let w = parse_who(&s, &who)?;
                    if let Who::Contact(a) = &w {
                        s.bot.authorized.insert(a.clone());
                    }
                    s.access.add_admin(w, how::SSH);
                }
                // Over SSH even the last admin may go: SSH is the way back in.
                AdminAction::Remove { who } => {
                    let w = parse_who(&s, &who)?;
                    if s.access.remove_admin(&w, false).is_err() {
                        bail!("{who} is not an admin");
                    }
                }
            }
            save_state(&state, &s)?;
        }
    }
    Ok(())
}

async fn run(path: &Path) -> Result<()> {
    let raw =
        std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    let cfg: Config = toml::from_str(&raw)?;
    let state = load_state(&cfg.station.state_path)?;
    let read = |p: &PathBuf| {
        std::fs::read_to_string(p).with_context(|| format!("reading {}", p.display()))
    };
    let code = match &cfg.access.code_file {
        Some(p) => Some(read(p)?),
        None => None,
    };
    let admin_password = match &cfg.access.admin_password_file {
        Some(p) => Some(read(p)?),
        None => None,
    };
    let scfg = StationCfg {
        code: match &code {
            Some(c) => Some(Arc::new(AccessKey::derive(c, &state.keyring.public())?)),
            None => None,
        },
        lanes: keryx::lane::LanePolicy {
            ofdm_capable: cfg.radio.ofdm_capable,
            ..Default::default()
        },
        ..StationCfg::default()
    };
    let mut station = Station::new(state, scfg);
    import(&cfg, &mut station)?;
    save_state(&cfg.station.state_path, station.state())?;
    let card = station.keyring().card().clone();
    let router_cfg = router::RouterCfg {
        access_code: code.map(|c| c.trim().to_owned()),
        station: format!("{} ({})", card.alias, cfg.station.callsign),
        ..Default::default()
    };
    // TODO(phase 1): feed frames, bridge messages and ticks to the hub; save
    // the state after each call, then carry out its outputs.
    let hub = hub::Hub::new(station, router_cfg, admin::Admin::new(admin_password));
    let station = &hub.station;
    let me = station.keyring().card();
    info!(callsign = %cfg.station.callsign, alias = %me.alias, short_id = me.key.short_id(), "Pharos starting");

    match cfg.radio.kind {
        RadioKind::Loopback => {
            info!("loopback radio: nothing to receive; waiting for Ctrl-C");
            // TODO(phase 1): spawn the PHY task; for each RxFrame call
            // station.on_rx(), every few seconds station.on_tick(); save the
            // state after each call, then carry out the effects in order:
            //   Transmit -> segment + PHY send on the lane (jitter if asked)
            //   others   -> deliver() to the bridges and the bot router
            let _ = deliver as fn(&Effect);
        }
        RadioKind::Js8 => bail!("the js8 radio backend is not implemented yet (phase 1)"),
    }
    tokio::signal::ctrl_c().await?;
    info!("stopping");
    Ok(())
}
