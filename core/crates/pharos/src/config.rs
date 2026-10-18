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
    pub bridges: BridgesCfg,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StationCfg {
    /// Sent in clear for identification (Spain: Art. 29, Orden IET/1311/2013).
    pub callsign: String,
    /// Session state from `keryx provision --me 0`. Holds key material: 0600.
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

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BridgesCfg {
    #[serde(default = "yes")]
    pub log: bool,
    pub matrix: Option<MatrixCfg>,
    pub ntfy: Option<NtfyCfg>,
}

impl Default for BridgesCfg {
    fn default() -> Self {
        Self {
            log: true,
            matrix: None,
            ntfy: None,
        }
    }
}

fn yes() -> bool {
    true
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MatrixCfg {
    pub homeserver: String,
    pub room_id: String,
    /// Name of the environment variable holding the access token.
    pub access_token_env: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NtfyCfg {
    pub url: String,
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
}
