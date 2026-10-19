//! `pharos` — 24/7 base station: decode every lane, ACK, store and
//! forward, and hand messages to the bridges.
//!
//! Status: skeleton. The station logic (`station.rs`) and outbox are real and
//! tested; the radio side is a loopback until the JS8 engine is wired in
//! (phase 1), and the Signal/WhatsApp/MeshCore bridges are TODO (the Phase 0
//! Python prototype covers Signal meanwhile).

// Skeleton: config fields, outbox and station effects are wired up once the
// PHY task lands (phase 1). Tests exercise them already.
#![allow(dead_code)]

mod config;
mod outbox;
mod station;

use anyhow::{Context, Result};
use clap::Parser;
use config::{Config, RadioKind};
use keryx::Message;
use station::{Effect, Station};
use std::path::PathBuf;
use tracing::{info, warn};

#[derive(Parser)]
#[command(name = "pharos", version, about = "Pharos, the Skypost base station")]
struct Args {
    #[arg(long, default_value = "base.toml")]
    config: PathBuf,
}

fn deliver(cfg: &Config, e: &Effect) {
    if let Effect::Deliver {
        header,
        message,
        snr_db,
    } = e
    {
        let text = message.render();
        if cfg.bridges.log {
            info!(
                from = header.src,
                counter = header.counter,
                encrypted = header.encrypted,
                snr_db,
                "{text}"
            );
        }
        if matches!(message, Message::Sos(_)) {
            warn!(from = header.src, "SOS received — alert every bridge");
        }
        // TODO(phase 1): Signal, WhatsApp and MeshCore sidecars (decision 0014).
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    let args = Args::parse();
    let raw = std::fs::read_to_string(&args.config)
        .with_context(|| format!("reading {}", args.config.display()))?;
    let cfg: Config = toml::from_str(&raw)?;
    let state = std::fs::read_to_string(&cfg.station.state_path)
        .with_context(|| format!("reading state {}", cfg.station.state_path.display()))?;
    let station = Station::new(serde_json::from_str(&state)?);
    info!(callsign = %cfg.station.callsign, me = station.me(), "base station starting");

    match cfg.radio.kind {
        RadioKind::Loopback => {
            info!("loopback radio: nothing to receive; waiting for Ctrl-C");
            // TODO(phase 1): spawn the PHY task, receive RxFrame values on a
            // channel, call station.on_rx(), carry out each Effect:
            //   Transmit -> segment + PHY send (lane from the peer's last SNR)
            //   Deliver  -> deliver() to bridges
            // and persist station.session() after every change.
            let _ = (&station, deliver as fn(&Config, &Effect));
        }
        RadioKind::Js8 => anyhow::bail!("the js8 radio backend is not implemented yet (phase 1)"),
    }
    tokio::signal::ctrl_c().await?;
    info!("stopping");
    Ok(())
}
