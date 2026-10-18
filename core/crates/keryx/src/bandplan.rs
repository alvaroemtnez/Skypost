//! Default band schedule for mainland Spain (see `docs/spec/band-plan.md`).
//!
//! INITIAL GUESS, not measured: approximate sunrise/sunset for Valencia in UTC
//! plus simple NVIS rules (40 m by day, 60 m at the transitions, 80 m at night).
//! Replace with data from the Phase 0 logs. Both ends must use the same table,
//! so it is versioned with the protocol.
//!
//! Known gap: on winter nights near solar minimum the critical frequency can
//! fall below 3.5 MHz before dawn. Then none of these bands reflects
//! vertically; only 160 m would (QMX+ yes, QDX no).

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Band {
    M80,
    M60,
    M40,
}

/// Approximate (sunrise, sunset) in decimal UTC hours for Valencia, by month.
const SUN_UTC: [(f32, f32); 12] = [
    (7.3, 16.5),
    (7.0, 17.0),
    (6.3, 17.6),
    (5.6, 18.2),
    (5.0, 18.7),
    (4.7, 19.1),
    (4.9, 19.1),
    (5.3, 18.6),
    (5.8, 17.8),
    (6.3, 17.0),
    (6.9, 16.4),
    (7.4, 16.3),
];

/// Primary band for a UTC time. `month` is 1..=12, `hour` is decimal UTC hours.
pub fn primary_band(month: u8, hour: f32) -> Band {
    let (rise, set) = SUN_UTC[usize::from(month.clamp(1, 12) - 1)];
    if hour >= rise + 1.5 && hour < set - 1.0 {
        Band::M40
    } else if (hour >= rise && hour < rise + 1.5) || (hour >= set - 1.0 && hour < set + 1.5) {
        Band::M60
    } else {
        Band::M80
    }
}

/// Primary band first, then fallbacks to try if it fails.
pub fn candidates(month: u8, hour: f32) -> [Band; 3] {
    match primary_band(month, hour) {
        Band::M40 => [Band::M40, Band::M60, Band::M80],
        Band::M60 => [Band::M60, Band::M40, Band::M80],
        Band::M80 => [Band::M80, Band::M60, Band::M40],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn day_transition_night() {
        assert_eq!(primary_band(1, 12.0), Band::M40);
        assert_eq!(primary_band(1, 7.5), Band::M60);
        assert_eq!(primary_band(1, 17.0), Band::M60);
        assert_eq!(primary_band(1, 22.0), Band::M80);
        assert_eq!(primary_band(1, 3.0), Band::M80);
        assert_eq!(candidates(6, 12.0)[0], Band::M40);
    }
}
