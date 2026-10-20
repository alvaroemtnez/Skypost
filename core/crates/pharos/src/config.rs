//! `base.toml` configuration. See `core/crates/pharos/base.example.toml`.

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

/// Access files (read on every start) and the first-run seeds of the access
/// switches (decision 0021): after the first run the switches live in the
/// state file and change by admin command or `pharos set`; these keys are
/// then ignored with a warning.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AccessCfg {
    /// File holding the access code (0600). A passphrase of four or more
    /// random words: it can be guessed offline. Proving it admits at once.
    pub code_file: Option<PathBuf>,
    /// File holding the admin password (0600). Without it, only admins in
    /// the state manage the Pharos remotely. Accepted from the bridges, and
    /// over the radio only in an encrypted frame.
    pub admin_password_file: Option<PathBuf>,
    /// First run only: anyone may use it (default false: closed).
    pub open: Option<bool>,
    /// First run only: take access requests while closed (default true).
    pub requests: Option<bool>,
    /// First run only: the admin password works (default true).
    pub login: Option<bool>,
    /// First run only: no discovery answers, no automatic messages (default false).
    pub stealth: Option<bool>,
    /// First run only: how long access requests stay pending (default 72).
    pub request_ttl_hours: Option<u64>,
}

impl AccessCfg {
    pub fn has_seeds(&self) -> bool {
        self.open.is_some()
            || self.requests.is_some()
            || self.login.is_some()
            || self.stealth.is_some()
            || self.request_ttl_hours.is_some()
    }
}

/// The bridge bot (docs/spec/pharos.md §2).
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BotCfg {
    /// First run only: admin accounts on the bridges ("+34600000000",
    /// "signal:user.01", "whatsapp:+34600000000").
    #[serde(default)]
    pub admins: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClientCfg {
    /// The field user's `skypost:id/...` URI (first run only, like `admin`
    /// and `contacts`: afterwards the allow list lives in the state).
    pub key: String,
    /// An admin, over the radio.
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
        assert_eq!(c.access.open, Some(false));
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
