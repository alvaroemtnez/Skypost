//! Compact 9-byte ACK, sized to fit one JS8 frame (see `docs/spec/frame-format.md`).
//!
//! ```text
//! byte 0: version | 0 | type = Ack
//! byte 1: src id | dst id        (src = the station acknowledging)
//! byte 2: low byte of the acknowledged counter
//! byte 3: SNR the acknowledging station measured, dB (i8)
//! byte 4: clock offset it measured, deciseconds (i8, + = sender is late)
//! byte 5-8: HMAC-SHA256(ack_key, bytes 0..5) truncated to 4 bytes
//! ```
//!
//! The ACK key is static per group (not ratcheted), so ACKs can be replayed.
//! Worst case a sender believes an old message was delivered; acceptable.

use crate::Error;
use crate::crypto::{ack_tag, subtle_eq};
use crate::header::{MsgType, VERSION, peek_type};
use serde::{Deserialize, Serialize};

pub const ACK_LEN: usize = 9;
const ACK_TAG_LEN: usize = 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ack {
    pub src: u8,
    pub dst: u8,
    pub acked_counter_lo: u8,
    pub snr_db: i8,
    pub dt_ds: i8,
}

impl Ack {
    pub fn encode(&self, ack_key: &[u8; 32]) -> Result<[u8; ACK_LEN], Error> {
        if self.src > 14 || self.dst > 15 {
            return Err(Error::Range("ack ids"));
        }
        let mut b = [0u8; ACK_LEN];
        b[0] = (VERSION << 6) | MsgType::Ack as u8;
        b[1] = (self.src << 4) | self.dst;
        b[2] = self.acked_counter_lo;
        b[3] = self.snr_db as u8;
        b[4] = self.dt_ds as u8;
        let t = ack_tag(ack_key, &b[..5]);
        b[5..].copy_from_slice(&t[..ACK_TAG_LEN]);
        Ok(b)
    }

    pub fn decode(b: &[u8], ack_key: &[u8; 32]) -> Result<Self, Error> {
        if b.len() < ACK_LEN {
            return Err(Error::TooShort);
        }
        if peek_type(b)? != MsgType::Ack {
            return Err(Error::Malformed("not an ACK"));
        }
        let t = ack_tag(ack_key, &b[..5]);
        if !subtle_eq(&t[..ACK_TAG_LEN], &b[5..ACK_LEN]) {
            return Err(Error::AuthFailed);
        }
        Ok(Self {
            src: b[1] >> 4,
            dst: b[1] & 0x0F,
            acked_counter_lo: b[2],
            snr_db: b[3] as i8,
            dt_ds: b[4] as i8,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_tamper() {
        let key = [3u8; 32];
        let a = Ack {
            src: 0,
            dst: 2,
            acked_counter_lo: 41,
            snr_db: -19,
            dt_ds: 7,
        };
        let mut b = a.encode(&key).unwrap();
        assert_eq!(b.len(), crate::JS8_FRAME_PAYLOAD_BYTES);
        assert_eq!(Ack::decode(&b, &key).unwrap(), a);
        b[3] = 0;
        assert_eq!(Ack::decode(&b, &key), Err(Error::AuthFailed));
    }
}
