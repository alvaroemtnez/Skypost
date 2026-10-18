//! `nvl` — command-line front end to `nvl-proto`.
//!
//! Used by the Phase 0 Python prototype (JS8Call glue) and for debugging.
//! It prints machine-readable output on stdout and errors on stderr.

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand, ValueEnum};
use data_encoding::{BASE32_NOPAD, HEXUPPER};
use nvl_proto::ack::Ack;
use nvl_proto::bandplan;
use nvl_proto::canned::PHRASES;
use nvl_proto::crypto::{GroupKey, KEY_LEN, Session};
use nvl_proto::lane::{LanePolicy, choose};
use nvl_proto::message::{self, Message};
use nvl_proto::position::Position;
use nvl_proto::segment::frames_needed;
use nvl_proto::{BROADCAST, Error, JS8_FRAME_PAYLOAD_BYTES};
use serde_json::json;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

const CODE_PREFIX: &str = "nvislink:v0";

#[derive(Parser)]
#[command(name = "nvl", version, about = "nvislink protocol tool")]
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
    /// Generate a new group provisioning code (the QR payload). Keep it secret.
    Keygen {
        #[arg(long, default_value_t = 8)]
        tag_len: usize,
    },
    /// Create a station state file from a provisioning code.
    Provision {
        /// Provisioning code, or @path to read it from a file.
        #[arg(long)]
        code: String,
        /// This station's member id (0 = base, 1..=14 = field stations).
        #[arg(long)]
        me: u8,
        #[arg(long)]
        state: PathBuf,
        #[arg(long)]
        force: bool,
    },
    /// Seal a message into a wire frame.
    Encode {
        #[arg(long)]
        state: PathBuf,
        #[arg(long, default_value_t = BROADCAST)]
        dst: u8,
        /// Encrypt the body. Default is signed plaintext (legal on air).
        #[arg(long)]
        encrypt: bool,
        #[arg(long, value_enum, default_value_t = Fmt::Base32)]
        format: Fmt,
        #[command(subcommand)]
        msg: MsgArg,
    },
    /// Verify and decode a wire frame (padded or not). Prints JSON.
    Decode {
        #[arg(long)]
        state: PathBuf,
        #[arg(long, value_enum, default_value_t = Fmt::Base32)]
        format: Fmt,
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
    Text { text: String },
    Canned { id: u8 },
    Ok(PosArgs),
    Sos(PosArgs),
    Pos(PosArgs),
    Poll,
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
    Encode {
        #[arg(long)]
        state: PathBuf,
        #[arg(long)]
        to: u8,
        #[arg(long)]
        counter: u16,
        #[arg(long, allow_negative_numbers = true)]
        snr: i8,
        #[arg(long, allow_negative_numbers = true, default_value_t = 0)]
        dt: i8,
    },
    Decode {
        #[arg(long)]
        state: PathBuf,
        data: String,
    },
}

fn parse_code(code: &str) -> Result<(GroupKey, usize)> {
    let code = match code.strip_prefix('@') {
        Some(path) => fs::read_to_string(path).with_context(|| format!("reading {path}"))?,
        None => code.to_owned(),
    };
    let parts: Vec<&str> = code.trim().split(':').collect();
    let [a, b, t, key] = parts.as_slice() else {
        bail!("expected {CODE_PREFIX}:t<N>:<key>")
    };
    if format!("{a}:{b}") != CODE_PREFIX {
        bail!("not a {CODE_PREFIX} code");
    }
    let tag_len: usize = t.strip_prefix('t').context("missing tag length")?.parse()?;
    let raw = BASE32_NOPAD
        .decode(key.as_bytes())
        .context("bad base32 key")?;
    let arr: [u8; KEY_LEN] = raw
        .try_into()
        .map_err(|_| anyhow::anyhow!("key must be 32 bytes"))?;
    Ok((GroupKey(arr), tag_len))
}

fn load(path: &Path) -> Result<Session> {
    let s =
        fs::read_to_string(path).with_context(|| format!("reading state {}", path.display()))?;
    Ok(serde_json::from_str(&s)?)
}

/// Atomic write, owner-only permissions: the state holds key material.
fn save(path: &Path, session: &Session) -> Result<()> {
    let tmp = path.with_extension("tmp");
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut f = opts.open(&tmp)?;
    f.write_all(serde_json::to_string_pretty(session)?.as_bytes())?;
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
        Cmd::Keygen { tag_len } => {
            let mut k = [0u8; KEY_LEN];
            getrandom::fill(&mut k).map_err(|e| anyhow::anyhow!("rng: {e}"))?;
            println!("{CODE_PREFIX}:t{tag_len}:{}", BASE32_NOPAD.encode(&k));
        }
        Cmd::Provision {
            code,
            me,
            state,
            force,
        } => {
            if state.exists() && !force {
                bail!(
                    "{} exists; reusing counters is unsafe, pass --force only for a fresh group key",
                    state.display()
                );
            }
            let (gk, tag_len) = parse_code(&code)?;
            save(&state, &Session::provision(&gk, me, tag_len)?)?;
            eprintln!("provisioned station {me} into {}", state.display());
        }
        Cmd::Encode {
            state,
            dst,
            encrypt,
            format,
            msg,
        } => {
            let mut s = load(&state)?;
            let m = match msg {
                MsgArg::Text { text } => Message::from_text(&text),
                MsgArg::Canned { id } => Message::Canned(id),
                MsgArg::Ok(p) => Message::StatusOk(p.get()?),
                MsgArg::Sos(p) => Message::Sos(p.get()?),
                MsgArg::Pos(p) => Message::Position(p.get()?.context("pos needs --lat and --lon")?),
                MsgArg::Poll => Message::Poll,
            };
            let counter = s.next_counter();
            let wire = message::encode(&mut s, &m, dst, encrypt)?;
            save(&state, &s)?; // persist the advanced counter before anything goes on air
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
                        "counter": counter,
                        "encrypted": encrypt,
                    })
                ),
            }
        }
        Cmd::Decode {
            state,
            format,
            data,
        } => {
            let mut s = load(&state)?;
            let bytes = parse_data(&data, format)?;
            let d = message::decode_padded(&mut s, &bytes)?;
            save(&state, &s)?;
            println!(
                "{}",
                json!({ "header": d.header, "message": d.message, "text": d.message.render() })
            );
        }
        Cmd::Ack { cmd } => match cmd {
            AckCmd::Encode {
                state,
                to,
                counter,
                snr,
                dt,
            } => {
                let s = load(&state)?;
                let a = Ack {
                    src: s.me,
                    dst: to,
                    acked_counter_lo: counter as u8,
                    snr_db: snr,
                    dt_ds: dt,
                };
                println!("{}", BASE32_NOPAD.encode(&a.encode(s.ack_key())?));
            }
            AckCmd::Decode { state, data } => {
                let s = load(&state)?;
                let a = Ack::decode(&parse_data(&data, Fmt::Base32)?, s.ack_key())?;
                println!("{}", serde_json::to_string(&a)?);
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
