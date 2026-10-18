//! Lanes (PHY speeds) and choosing one from the SNR the other end reported.
//! See `docs/spec/arq-and-lanes.md`.

use serde::{Deserialize, Serialize};

/// Ordered from most robust to fastest.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum Lane {
    Js8Slow,
    Js8Normal,
    Js8Fast,
    Js8Turbo,
    /// Mercury (codec2 OFDM data modes). Needs a linear SSB radio, not the QDX.
    OfdmFast,
}

impl Lane {
    pub const LADDER: [Lane; 5] = [
        Lane::Js8Slow,
        Lane::Js8Normal,
        Lane::Js8Fast,
        Lane::Js8Turbo,
        Lane::OfdmFast,
    ];

    /// Published decode floor in dB (2.5 kHz reference for JS8, 3 kHz for
    /// Mercury's DATAC15 90 % AWGN point). Sources in `docs/research-notes.md`.
    pub fn floor_snr_db(self) -> f32 {
        match self {
            Lane::Js8Slow => -28.0,
            Lane::Js8Normal => -24.0,
            Lane::Js8Fast => -20.0,
            Lane::Js8Turbo => -18.0,
            Lane::OfdmFast => -9.5,
        }
    }

    /// T/R period per frame in seconds. For OFDM this is a rough per-burst
    /// figure; Mercury runs its own ARQ inside a session.
    pub fn frame_secs(self) -> u32 {
        match self {
            Lane::Js8Slow => 30,
            Lane::Js8Normal => 15,
            Lane::Js8Fast => 10,
            Lane::Js8Turbo => 6,
            Lane::OfdmFast => 5,
        }
    }

    pub fn more_robust(self) -> Option<Lane> {
        let i = Self::LADDER.iter().position(|&l| l == self).unwrap();
        i.checked_sub(1).map(|j| Self::LADDER[j])
    }

    pub fn is_js8(self) -> bool {
        self != Lane::OfdmFast
    }
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
pub struct LanePolicy {
    /// Safety margin above a lane's floor before using it (fading, QSB).
    pub margin_db: f32,
    /// Whether this station's radio can transmit OFDM (G90 yes, QDX no).
    pub ofdm_capable: bool,
    /// Lane used before any SNR report has arrived.
    pub initial: Lane,
}

impl Default for LanePolicy {
    fn default() -> Self {
        Self {
            margin_db: 6.0,
            ofdm_capable: false,
            initial: Lane::Js8Normal,
        }
    }
}

/// Fastest lane whose floor plus margin is at or below the reported SNR.
/// No report yet → `policy.initial`. Nothing qualifies → `Js8Slow`.
pub fn choose(reported_snr_db: Option<f32>, policy: &LanePolicy) -> Lane {
    let Some(snr) = reported_snr_db else {
        return policy.initial;
    };
    Lane::LADDER
        .iter()
        .rev()
        .copied()
        .filter(|l| policy.ofdm_capable || l.is_js8())
        .find(|l| l.floor_snr_db() + policy.margin_db <= snr)
        .unwrap_or(Lane::Js8Slow)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn choice_follows_snr_and_capability() {
        let qdx = LanePolicy::default();
        let g90 = LanePolicy {
            ofdm_capable: true,
            ..Default::default()
        };
        assert_eq!(choose(None, &qdx), Lane::Js8Normal);
        assert_eq!(choose(Some(-30.0), &qdx), Lane::Js8Slow);
        assert_eq!(choose(Some(-17.0), &qdx), Lane::Js8Normal);
        assert_eq!(choose(Some(-11.0), &qdx), Lane::Js8Turbo);
        assert_eq!(choose(Some(0.0), &qdx), Lane::Js8Turbo);
        assert_eq!(choose(Some(0.0), &g90), Lane::OfdmFast);
        assert_eq!(Lane::Js8Slow.more_robust(), None);
        assert_eq!(Lane::OfdmFast.more_robust(), Some(Lane::Js8Turbo));
    }
}
