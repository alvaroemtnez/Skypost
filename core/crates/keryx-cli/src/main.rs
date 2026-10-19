//! `keryx` — command-line front end to `keryx`.
//!
//! Used by the Phase 0 Python prototype (JS8Call glue) and for debugging.
//! It prints machine-readable output on stdout and errors on stderr.

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand, ValueEnum};
use data_encoding::{BASE32_NOPAD, HEXUPPER};
use keryx::access::{AccessKey, NO_PROOF};
use keryx::bandplan;
use keryx::bridge::Address;
use keryx::canned::PHRASES;
use keryx::lane::{LanePolicy, choose};
use keryx::message::{self, Message, Received};
use keryx::position::Position;
use keryx::segment::frames_needed;
use keryx::uri::{SkypostUri, valid_alias, valid_callsign};
use keryx::{
    Error, GroupId, Identity, JS8_FRAME_PAYLOAD_BYTES, Keyring, LinkRef, PeerKind, PublicKey,
};
use serde_json::{Value, json};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Parser)]
#[command(name = "keryx", version, about = "Keryx protocol tool")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Clone, Copy, ValueEnum)]
enum Fmt {
    Base32,
    Hex,
    Json,
}

#[derive(Subcommand)]
enum Cmd {
    /// Create a new identity and keyring state file. Prints its skypost: URI.
    Keygen {
        #[arg(long)]
        state: PathBuf,
        #[arg(long)]
        alias: String,
        #[arg(long)]
        callsign: Option<String>,
        /// This station is a Pharos.
        #[arg(long)]
        pharos: bool,
        #[arg(long)]
        force: bool,
    },
    /// Print this station's skypost: URI (the QR payload) and safety number.
    Id {
        #[arg(long)]
        state: PathBuf,
    },
    /// Add a contact or Pharos, or join a group, from a skypost: URI.
    Add {
        #[arg(long)]
        state: PathBuf,
        /// Fingerprint checked in person or by phone.
        #[arg(long)]
        verified: bool,
        uri: String,
    },
    /// Contacts, Pharos stations and groups as JSON.
    List {
        #[arg(long)]
        state: PathBuf,
    },
    /// Seal a message into a wire frame.
    Encode {
        #[arg(long)]
        state: PathBuf,
        /// Peer alias or base32 key, or `group:<name>`.
        #[arg(long)]
        to: String,
        /// Encrypt the body. Default is signed plaintext (legal on air).
        #[arg(long)]
        encrypt: bool,
        #[arg(long, value_enum, default_value_t = Fmt::Base32)]
        format: Fmt,
        #[command(subcommand)]
        msg: MsgArg,
    },
    /// Build an unauthenticated Discover frame.
    Discover {
        #[arg(long)]
        state: PathBuf,
        /// Query id; random if omitted.
        #[arg(long)]
        query: Option<u16>,
    },
    /// Build an unauthenticated Announce frame answering a Discover.
    Announce {
        #[arg(long)]
        state: PathBuf,
        #[arg(long)]
        to_short: u8,
        #[arg(long)]
        query: u16,
        #[arg(long, default_value_t = 1)]
        flags: u8,
    },
    /// Verify and decode a frame (padded or not). Prints JSON. A duplicate
    /// exits with 3 and still prints who to re-ACK.
    Decode {
        #[arg(long)]
        state: PathBuf,
        #[arg(long, value_enum, default_value_t = Fmt::Base32)]
        format: Fmt,
        /// Add the sender of a valid Register as a peer.
        #[arg(long)]
        accept_register: bool,
        data: String,
    },
    /// Build or check a compact ACK frame.
    Ack {
        #[command(subcommand)]
        cmd: AckCmd,
    },
    /// List the stock phrases and their ids.
    Phrases,
    /// Default band for a UTC month and hour.
    Band {
        #[arg(long)]
        month: u8,
        #[arg(long)]
        hour: f32,
    },
    /// Lane the sender would pick for a reported SNR.
    Lane {
        #[arg(long, allow_negative_numbers = true)]
        snr: Option<f32>,
        #[arg(long)]
        ofdm: bool,
    },
}

#[derive(Subcommand)]
enum MsgArg {
    Text {
        text: String,
    },
    Canned {
        id: u8,
    },
    Ok(PosArgs),
    Sos(PosArgs),
    Pos(PosArgs),
    /// Compact ping; `--listen` minutes the sender keeps receiving.
    Poll {
        #[arg(long, default_value_t = 10)]
        listen: u8,
    },
    /// Register this station with a Pharos.
    Register {
        /// The Pharos access code, if it has one.
        #[arg(long)]
        code: Option<String>,
    },
    /// Introduce this station to a group (`--to group:<name>`).
    Join,
    /// Bind a handle to a bridged contact.
    BridgeOpen {
        #[arg(long)]
        handle: u8,
        #[command(flatten)]
        addr: AddrArgs,
        #[arg(long)]
        name: Option<String>,
    },
    /// Text to the bridged contact bound to `handle`.
    Bridged {
        #[arg(long)]
        handle: u8,
        text: String,
    },
}

#[derive(clap::Args)]
#[group(required = true, multiple = false)]
struct AddrArgs {
    #[arg(long)]
    whatsapp: Option<String>,
    #[arg(long)]
    signal: Option<String>,
    #[arg(long)]
    signal_user: Option<String>,
}

impl AddrArgs {
    fn get(self) -> Address {
        match (self.whatsapp, self.signal, self.signal_user) {
            (Some(p), _, _) => Address::WhatsappPhone(Address::normalize_phone(&p)),
            (_, Some(p), _) => Address::SignalPhone(Address::normalize_phone(&p)),
            (_, _, Some(u)) => Address::SignalUsername(u),
            _ => unreachable!("clap requires one"),
        }
    }
}

#[derive(clap::Args)]
struct PosArgs {
    #[arg(long, allow_negative_numbers = true)]
    lat: Option<f64>,
    #[arg(long, allow_negative_numbers = true)]
    lon: Option<f64>,
}

impl PosArgs {
    fn get(&self) -> Result<Option<Position>> {
        match (self.lat, self.lon) {
            (Some(lat), Some(lon)) => Ok(Some(Position { lat, lon })),
            (None, None) => Ok(None),
            _ => bail!("give both --lat and --lon"),
        }
    }
}

#[derive(Subcommand)]
enum AckCmd {
    /// ACK a frame received from `--to` (on a group link with `--group`).
    Encode {
        #[arg(long)]
        state: PathBuf,
        /// Sender's base32 key or alias.
        #[arg(long)]
        to: String,
        /// Group id (base32) when the frame came on a group link.
        #[arg(long)]
        group: Option<String>,
        #[arg(long)]
        counter: u16,
        #[arg(long, allow_negative_numbers = true)]
        snr: i8,
        #[arg(long, allow_negative_numbers = true, default_value_t = 0)]
        dt: i8,
        #[arg(long)]
        more: bool,
    },
    /// Check an ACK for a frame this station sent to `--to` with `--counter`.
    Check {
        #[arg(long)]
        state: PathBuf,
        /// Peer alias or base32 key, or `group:<name>`.
        #[arg(long)]
        to: String,
        #[arg(long)]
        counter: u16,
        data: String,
    },
}

fn load(path: &Path) -> Result<Keyring> {
    let s =
        fs::read_to_string(path).with_context(|| format!("reading state {}", path.display()))?;
    Ok(serde_json::from_str(&s)?)
}

/// Atomic write, owner-only permissions: the state holds key material.
fn save(path: &Path, keyring: &Keyring) -> Result<()> {
    let tmp = path.with_extension("tmp");
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut f = opts.open(&tmp)?;
    f.write_all(serde_json::to_string_pretty(keyring)?.as_bytes())?;
    f.sync_all()?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn parse_data(data: &str, fmt: Fmt) -> Result<Vec<u8>> {
    let d = data.trim().to_uppercase();
    Ok(match fmt {
        Fmt::Hex => HEXUPPER.decode(d.as_bytes())?,
        _ => BASE32_NOPAD.decode(d.as_bytes())?,
    })
}

/// `group:<name>`, a peer alias, or a base32 public key.
fn resolve(k: &Keyring, target: &str) -> Result<LinkRef> {
    if let Some(name) = target.strip_prefix("group:") {
        return k
            .groups()
            .find(|g| g.name == name || g.id().to_base32() == name)
            .map(|g| LinkRef::Group(g.id()))
            .with_context(|| format!("no group {name}"));
    }
    if let Ok(key) = PublicKey::from_base32(target)
        && k.peer(&key).is_some()
    {
        return Ok(LinkRef::Peer(key));
    }
    let mut m = k
        .peers()
        .filter(|p| p.card.alias.eq_ignore_ascii_case(target));
    match (m.next(), m.next()) {
        (Some(p), None) => Ok(LinkRef::Peer(p.card.key)),
        (Some(_), Some(_)) => bail!("alias {target} is ambiguous; use the key"),
        _ => bail!("no contact or Pharos {target}"),
    }
}

fn peer_key(link: LinkRef) -> Result<PublicKey> {
    match link {
        LinkRef::Peer(k) => Ok(k),
        LinkRef::Group(_) => bail!("expected a contact or Pharos, not a group"),
    }
}

fn link_json(k: &Keyring, link: &LinkRef) -> Value {
    match link {
        LinkRef::Peer(p) => json!({ "peer": p, "alias": k.peer(p).map(|p| p.card.alias.clone()) }),
        LinkRef::Group(g) => json!({ "group": g, "name": k.group(g).map(|g| g.name.clone()) }),
    }
}

fn main() {
    if let Err(e) = run() {
        eprintln!("error: {e:#}");
        std::process::exit(match e.downcast_ref::<Error>() {
            Some(Error::Duplicate { .. }) => 3,
            Some(_) => 2,
            None => 1,
        });
    }
}

fn run() -> Result<()> {
    match Cli::parse().cmd {
        Cmd::Keygen {
            state,
            alias,
            callsign,
            pharos,
            force,
        } => {
            if state.exists() && !force {
                bail!(
                    "{} exists; a new identity loses every contact and counter",
                    state.display()
                );
            }
            if !valid_alias(&alias) {
                bail!("alias: 1-16 letters, digits, '_', '-' or '.'");
            }
            let callsign = callsign.map(|c| c.to_ascii_uppercase());
            if callsign.as_deref().is_some_and(|c| !valid_callsign(c)) {
                bail!("invalid callsign");
            }
            let kind = if pharos {
                PeerKind::Pharos
            } else {
                PeerKind::Person
            };
            let k = Keyring::new(Identity::generate()?, &alias, callsign.as_deref(), kind);
            save(&state, &k)?;
            println!("{}", SkypostUri::Id(k.card().clone()).format());
        }
        Cmd::Id { state } => {
            let k = load(&state)?;
            println!(
                "{}",
                json!({
                    "uri": SkypostUri::Id(k.card().clone()).format(),
                    "safety_number": k.public().safety_number(),
                    "short_id": k.short_id(),
                })
            );
        }
        Cmd::Add {
            state,
            verified,
            uri,
        } => {
            let mut k = load(&state)?;
            let out = match SkypostUri::parse(&uri)? {
                SkypostUri::Id(card) => {
                    let j = json!({ "added": card.alias, "key": card.key, "kind": card.kind });
                    k.add_peer(card, verified)?;
                    j
                }
                SkypostUri::Group { key, name } => {
                    let id = k.add_group(key, &name);
                    json!({ "joined": name, "group": id })
                }
            };
            save(&state, &k)?;
            println!("{out}");
        }
        Cmd::List { state } => {
            let k = load(&state)?;
            let peers: Vec<Value> = k
                .peers()
                .map(|p| json!({ "card": p.card, "verified": p.verified, "short_id": p.card.key.short_id() }))
                .collect();
            let groups: Vec<Value> = k
                .groups()
                .map(|g| json!({ "name": g.name, "id": g.id(), "members": g.roster.values().collect::<Vec<_>>() }))
                .collect();
            println!(
                "{}",
                json!({ "me": k.card(), "peers": peers, "groups": groups })
            );
        }
        Cmd::Encode {
            state,
            to,
            encrypt,
            format,
            msg,
        } => {
            let mut k = load(&state)?;
            let link = resolve(&k, &to)?;
            let alias = k.card().alias.clone();
            let m = match msg {
                MsgArg::Text { text } => Message::from_text(&text),
                MsgArg::Canned { id } => Message::Canned(id),
                MsgArg::Ok(p) => Message::StatusOk(p.get()?),
                MsgArg::Sos(p) => Message::Sos(p.get()?),
                MsgArg::Pos(p) => Message::Position(p.get()?.context("pos needs --lat and --lon")?),
                MsgArg::Poll { listen } => Message::Poll { listen_min: listen },
                MsgArg::Register { code } => {
                    let pharos = match link {
                        LinkRef::Peer(p) => p,
                        LinkRef::Group(_) => bail!("register goes to a Pharos"),
                    };
                    let proof = match code {
                        Some(c) => AccessKey::derive(&c, &pharos)?.proof(&k.public(), &pharos),
                        None => NO_PROOF,
                    };
                    Message::Register {
                        key: k.public(),
                        proof,
                        alias,
                    }
                }
                MsgArg::Join => Message::GroupJoin {
                    key: k.public(),
                    alias,
                },
                MsgArg::BridgeOpen { handle, addr, name } => Message::BridgeOpen {
                    handle,
                    address: addr.get(),
                    name,
                },
                MsgArg::Bridged { handle, text } => Message::Bridged {
                    handle,
                    inner: Box::new(Message::from_text(&text)),
                },
            };
            let s = message::encode(&mut k, &m, &link, encrypt)?;
            save(&state, &k)?; // persist the advanced counter before anything goes on air
            let wire = s.wire;
            match format {
                Fmt::Base32 => println!("{}", BASE32_NOPAD.encode(&wire)),
                Fmt::Hex => println!("{}", HEXUPPER.encode(&wire)),
                Fmt::Json => println!(
                    "{}",
                    json!({
                        "base32": BASE32_NOPAD.encode(&wire),
                        "hex": HEXUPPER.encode(&wire),
                        "bytes": wire.len(),
                        "js8_frames": frames_needed(wire.len(), JS8_FRAME_PAYLOAD_BYTES),
                        "counter": s.counter,
                        "encrypted": encrypt,
                        "link": link,
                    })
                ),
            }
        }
        Cmd::Discover { state, query } => {
            let k = load(&state)?;
            let q = match query {
                Some(q) => q,
                None => u16::from_be_bytes(keryx::crypto::random_bytes()?),
            };
            let wire = message::encode_discover(&k, q)?;
            println!(
                "{}",
                json!({ "base32": BASE32_NOPAD.encode(&wire), "query": q })
            );
        }
        Cmd::Announce {
            state,
            to_short,
            query,
            flags,
        } => {
            let k = load(&state)?;
            println!(
                "{}",
                BASE32_NOPAD.encode(&message::encode_announce(&k, flags, to_short, query)?)
            );
        }
        Cmd::Decode {
            state,
            format,
            accept_register,
            data,
        } => {
            let mut k = load(&state)?;
            let bytes = parse_data(&data, format)?;
            let r = match message::decode(&mut k, &bytes) {
                Ok(r) => r,
                Err(e @ Error::Duplicate { .. }) => {
                    if let Error::Duplicate {
                        link,
                        sender,
                        counter,
                    } = &e
                    {
                        println!(
                            "{}",
                            json!({ "kind": "duplicate", "link": link_json(&k, link), "sender": sender, "counter": counter })
                        );
                    }
                    return Err(e.into());
                }
                Err(e) => return Err(e.into()),
            };
            let out = match r {
                Received::Message(d) => {
                    if accept_register
                        && d.new_peer
                        && let Message::Register { key, alias, .. } = &d.message
                    {
                        let card = keryx::Card {
                            key: *key,
                            alias: alias.clone(),
                            callsign: None,
                            kind: PeerKind::Person,
                        };
                        k.accept_register(card, d.header.counter)?;
                    }
                    json!({
                        "kind": "message",
                        "header": d.header,
                        "link": link_json(&k, &d.link),
                        "sender": d.sender,
                        "message": d.message,
                        "text": d.message.render(),
                        "new_peer": d.new_peer,
                    })
                }
                Received::Discover { src, query } => {
                    json!({ "kind": "discover", "src": src, "query": query })
                }
                Received::Announce {
                    dst,
                    query,
                    card,
                    flags,
                } => {
                    json!({ "kind": "announce", "dst": dst, "query": query, "card": card, "flags": flags,
                            "uri": SkypostUri::Id(card.clone()).format() })
                }
            };
            save(&state, &k)?;
            println!("{out}");
        }
        Cmd::Ack { cmd } => match cmd {
            AckCmd::Encode {
                state,
                to,
                group,
                counter,
                snr,
                dt,
                more,
            } => {
                let k = load(&state)?;
                let (link, sender) = match group {
                    Some(g) => (
                        LinkRef::Group(GroupId::from_base32(&g)?),
                        PublicKey::from_base32(&to)?,
                    ),
                    None => {
                        let p = match PublicKey::from_base32(&to) {
                            Ok(p) => p,
                            Err(_) => peer_key(resolve(&k, &to)?)?,
                        };
                        (LinkRef::Peer(p), p)
                    }
                };
                println!(
                    "{}",
                    BASE32_NOPAD.encode(&k.ack(&link, &sender, counter, snr, dt, more)?)
                );
            }
            AckCmd::Check {
                state,
                to,
                counter,
                data,
            } => {
                let k = load(&state)?;
                let link = resolve(&k, &to)?;
                let a = k.open_ack(&parse_data(&data, Fmt::Base32)?, &[(link, counter)])?;
                println!(
                    "{}",
                    json!({ "acker": a.acker, "counter": a.counter, "snr_db": a.snr_db, "dt_ds": a.dt_ds, "more": a.more })
                );
            }
        },
        Cmd::Phrases => {
            for (i, p) in PHRASES.iter().enumerate() {
                println!("{i:>3}  {p}");
            }
        }
        Cmd::Band { month, hour } => {
            println!("{:?}", bandplan::candidates(month, hour));
        }
        Cmd::Lane { snr, ofdm } => {
            let policy = LanePolicy {
                ofdm_capable: ofdm,
                ..Default::default()
            };
            println!("{:?}", choose(snr, &policy));
        }
    }
    Ok(())
}
