//! `base.toml` configuration. See `core/crates/pharos/base.example.toml`.

use crate::router::Inbound;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub station: StationCfg,
    #[serde(default)]
    pub radio: RadioCfg,
    #[serde(default)]
    pub access: AccessCfg,
    #[serde(default)]
    pub bot: BotCfg,
    /// Field users on the allow list.
    #[serde(default)]
    pub clients: Vec<ClientCfg>,
    #[serde(default)]
    pub bridges: BridgesCfg,
}

#[derive(Debug, Default, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AccessMode {
    /// Anyone may register.
    Open,
    /// Field users and bridged contacts need the access code.
    Code,
    /// Allow list only; others wait for an admin.
    #[default]
    Closed,
}

/// Who may use this Pharos (docs/spec/pharos.md, "Access").
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AccessCfg {
    #[serde(default)]
    pub mode: AccessMode,
    /// File holding the access code (mode "code"); keep it 0600. A
    /// passphrase of four or more random words: it can be guessed offline.
    pub code_file: Option<PathBuf>,
    /// Answer discovery requests. Off: only clients with the QR find it.
    #[serde(default = "yes")]
    pub discoverable: bool,
    /// File holding the admin password (0600). Without it only the admins in
    /// the config may manage the Pharos remotely. Accepted from the bridges,
    /// and over the radio only in an encrypted frame (decision 0020).
    pub admin_password_file: Option<PathBuf>,
}

impl Default for AccessCfg {
    fn default() -> Self {
        Self {
            mode: AccessMode::default(),
            code_file: None,
            discoverable: true,
            admin_password_file: None,
        }
    }
}

/// The bridge bot (docs/spec/pharos.md §2).
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BotCfg {
    /// Who on Signal/WhatsApp/MeshCore may use the bot.
    #[serde(default)]
    pub inbound: Inbound,
    /// Admin accounts on the bridges ("+34600000000", "signal:user.01").
    #[serde(default)]
    pub admins: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClientCfg {
    /// The field user's `skypost:id/...` URI.
    pub key: String,
    /// May send admin commands over the radio.
    #[serde(default)]
    pub admin: bool,
    /// Bridged contacts authorised to write first (phone numbers, usernames).
    #[serde(default)]
    pub contacts: Vec<String>,
    /// Who gets this user's SOS on every bridge.
    #[serde(default)]
    pub sos: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StationCfg {
    /// Sent in clear for identification (Spain: Art. 29, Orden IET/1311/2013).
    pub callsign: String,
    /// State from `pharos init` (keyring, registry, queue). Key material: 0600.
    pub state_path: PathBuf,
}

#[derive(Debug, Default, Deserialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RadioKind {
    /// In-process loopback, for development without RF.
    #[default]
    Loopback,
    /// JS8 engine on a USB audio + CAT radio (phase 1, not implemented yet).
    Js8,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RadioCfg {
    #[serde(default)]
    pub kind: RadioKind,
    #[serde(default)]
    pub ofdm_capable: bool,
    /// Dial frequencies in kHz. TBD: pick inside the IARU Region 1 segments
    /// for automatically controlled data stations; see docs/spec/band-plan.md.
    pub dial_khz_80m: Option<f64>,
    pub dial_khz_60m: Option<f64>,
    pub dial_khz_40m: Option<f64>,
}

/// Bridges run as sidecar processes; Pharos only speaks JSON to them
/// (docs/decisions/0014-bridges-sidecars.md).
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BridgesCfg {
    #[serde(default = "yes")]
    pub log: bool,
    pub signal: Option<SignalCfg>,
    pub whatsapp: Option<WhatsappCfg>,
    pub meshcore: Option<MeshcoreCfg>,
}

impl Default for BridgesCfg {
    fn default() -> Self {
        Self {
            log: true,
            signal: None,
            whatsapp: None,
            meshcore: None,
        }
    }
}

fn yes() -> bool {
    true
}

/// `signal-cli -a <account> daemon --socket <socket>`, JSON-RPC.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SignalCfg {
    /// The base station's own Signal number, e.g. "+34600000000".
    pub account: String,
    /// Unix socket of the signal-cli daemon.
    pub socket: PathBuf,
}

/// `wacli`, linked as a WhatsApp Web device of a dedicated number.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WhatsappCfg {
    #[serde(default = "wacli")]
    pub bin: PathBuf,
}

fn wacli() -> PathBuf {
    "wacli".into()
}

/// The `sidecars/meshcore` process (meshcore_py), JSON lines on stdio.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MeshcoreCfg {
    /// Command line that starts the sidecar.
    pub command: Vec<String>,
    /// Companion node: a serial device ("/dev/ttyUSB0") or "host:port".
    pub device: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn example_config_parses() {
        let s = include_str!("../base.example.toml");
        let c: Config = toml::from_str(s).unwrap();
        assert_eq!(c.radio.kind, RadioKind::Loopback);
        assert!(c.bridges.log);
    }

    #[test]
    fn example_bridges_parse_when_uncommented() {
        let s = include_str!("../base.example.toml")
            .replace("# [bridges.", "[bridges.")
            .replace("# [[clients]]", "[[clients]]");
        let s: String = s
            .lines()
            .map(|l| match l.strip_prefix("# ") {
                Some(kv) if kv.contains(" = ") && !kv.starts_with("dial_") => kv,
                _ => l,
            })
            .collect::<Vec<_>>()
            .join("\n");
        let c: Config = toml::from_str(&s).unwrap();
        assert_eq!(c.bridges.signal.unwrap().account, "+34600000000");
        assert_eq!(c.bridges.whatsapp.unwrap().bin, PathBuf::from("wacli"));
        assert_eq!(c.bridges.meshcore.unwrap().command[0], "python3");
        assert!(c.clients[0].key.starts_with("skypost:id/"));
    }
}
