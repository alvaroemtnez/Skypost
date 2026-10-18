//! 6-byte position: 24-bit latitude + 24-bit longitude.
//! Resolution is about 1.2 m north-south and under 2.4 m east-west.

use crate::Error;
use serde::{Deserialize, Serialize};

pub const POSITION_LEN: usize = 6;
const SCALE: f64 = ((1u32 << 24) - 1) as f64;

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Position {
    pub lat: f64,
    pub lon: f64,
}

impl Position {
    pub fn encode(&self) -> Result<[u8; POSITION_LEN], Error> {
        if !(-90.0..=90.0).contains(&self.lat) || !(-180.0..=180.0).contains(&self.lon) {
            return Err(Error::Range("latitude/longitude"));
        }
        let la = ((self.lat + 90.0) / 180.0 * SCALE).round() as u32;
        let lo = ((self.lon + 180.0) / 360.0 * SCALE).round() as u32;
        let a = la.to_be_bytes();
        let b = lo.to_be_bytes();
        Ok([a[1], a[2], a[3], b[1], b[2], b[3]])
    }

    pub fn decode(b: &[u8]) -> Result<Self, Error> {
        if b.len() != POSITION_LEN {
            return Err(Error::Malformed("position must be 6 bytes"));
        }
        let la = u32::from_be_bytes([0, b[0], b[1], b[2]]) as f64;
        let lo = u32::from_be_bytes([0, b[3], b[4], b[5]]) as f64;
        Ok(Self {
            lat: la / SCALE * 180.0 - 90.0,
            lon: lo / SCALE * 360.0 - 180.0,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_within_a_few_metres() {
        let p = Position {
            lat: 39.4817,
            lon: -0.3437,
        };
        let q = Position::decode(&p.encode().unwrap()).unwrap();
        assert!((p.lat - q.lat).abs() < 1e-5);
        assert!((p.lon - q.lon).abs() < 2.5e-5);
    }
}
