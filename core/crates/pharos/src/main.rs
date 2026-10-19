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

mod config;
mod outbox;
mod registry;
mod router;
mod station;

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};
use config::{Config, RadioKind};
use keryx::uri::{SkypostUri, valid_alias, valid_callsign};
use keryx::{Identity, Keyring, PeerKind};
use station::{Effect, State, Station, StationCfg};
use std::io::Write;
use std::path::{Path, PathBuf};
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
        Effect::Inbox { alias, message, .. } => info!(%alias, "{}", message.render()),
        Effect::ApprovalNeeded { alias, key } => {
            warn!(%alias, safety = %key.safety_number(), "registration needs approval");
        }
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
            let s = State {
                keyring: k,
                registry: Default::default(),
                outbox: Default::default(),
            };
            save_state(&state, &s)?;
            println!("{}", SkypostUri::Id(s.keyring.card().clone()).format());
        }
        Cmd::Identity { state } => {
            let s = load_state(&state)?;
            println!("{}", SkypostUri::Id(s.keyring.card().clone()).format());
            eprintln!("safety number: {}", s.keyring.public().safety_number());
        }
        Cmd::Run { config } => run(&config).await?,
    }
    Ok(())
}

async fn run(path: &Path) -> Result<()> {
    let raw =
        std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    let cfg: Config = toml::from_str(&raw)?;
    let state = load_state(&cfg.station.state_path)?;
    let scfg = StationCfg {
        registration: cfg.registration.mode,
        lanes: keryx::lane::LanePolicy {
            ofdm_capable: cfg.radio.ofdm_capable,
            ..Default::default()
        },
        ..StationCfg::default()
    };
    let mut station = Station::new(state, scfg);
    for c in &cfg.clients {
        match SkypostUri::parse(&c.key)? {
            SkypostUri::Id(card) => station.allow(card)?,
            SkypostUri::Group { .. } => bail!("[[clients]] key must be a skypost:id/ URI"),
        }
    }
    save_state(&cfg.station.state_path, station.state())?;
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
