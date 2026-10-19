//! Compact 9-byte ACK, sized to fit one JS8 frame (see `docs/spec/frame-format.md`).
//!
//! ```text
//! byte 0: version | more | type = Ack
//! byte 1: src id | dst id        (src = the station acknowledging)
//! byte 2: low byte of the acknowledged counter
//! byte 3: SNR the acknowledging station measured, dB (i8)
//! byte 4: clock offset it measured, deciseconds (i8, + = sender is late)
//! byte 5-8: HMAC-SHA256(ack key, bytes 0..5 ‖ full counter BE)[0..4]
//! ```
//!
//! The ACK key belongs to the acknowledging station on that link
//! ([`crate::keyring::Keyring::ack`]). Binding the full counter means an old
//! ACK cannot be replayed for a later message with the same low byte.

use crate::Error;
use crate::crypto::{COMPACT_TAG_LEN, hmac, subtle_eq};
use crate::header::{BROADCAST, MAX_SHORT_ID, MsgType, peek_type};
use crate::keyring::byte0;
use serde::{Deserialize, Serialize};

pub const ACK_LEN: usize = 9;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ack {
    /// The acknowledging Pharos holds queued messages for `dst`.
    pub more: bool,
    pub src: u8,
    pub dst: u8,
    pub acked_counter_lo: u8,
    pub snr_db: i8,
    pub dt_ds: i8,
}

impl Ack {
    pub fn encode(&self, ack_key: &[u8; 32], counter: u16) -> Result<[u8; ACK_LEN], Error> {
        if self.src > MAX_SHORT_ID || self.dst > BROADCAST {
            return Err(Error::Range("ack ids"));
        }
        if counter as u8 != self.acked_counter_lo {
            return Err(Error::Range("counter low byte mismatch"));
        }
        let mut b = [0u8; ACK_LEN];
        b[0] = byte0(MsgType::Ack, self.more);
        b[1] = (self.src << 4) | self.dst;
        b[2] = self.acked_counter_lo;
        b[3] = self.snr_db as u8;
        b[4] = self.dt_ds as u8;
        let t = hmac(ack_key, &[&b[..5], &counter.to_be_bytes()]);
        b[5..].copy_from_slice(&t[..COMPACT_TAG_LEN]);
        Ok(b)
    }

    /// Parse without verifying (to find who it is for).
    pub fn peek(b: &[u8]) -> Result<Self, Error> {
        if b.len() < ACK_LEN {
            return Err(Error::TooShort);
        }
        if peek_type(b)? != MsgType::Ack {
            return Err(Error::Malformed("not an ACK"));
        }
        Ok(Self {
            more: b[0] & 0x20 != 0,
            src: b[1] >> 4,
            dst: b[1] & 0x0F,
            acked_counter_lo: b[2],
            snr_db: b[3] as i8,
            dt_ds: b[4] as i8,
        })
    }

    pub fn verify(b: &[u8], ack_key: &[u8; 32], counter: u16) -> Result<Self, Error> {
        let a = Self::peek(b)?;
        let t = hmac(ack_key, &[&b[..5], &counter.to_be_bytes()]);
        if !subtle_eq(&t[..COMPACT_TAG_LEN], &b[5..ACK_LEN]) {
            return Err(Error::AuthFailed);
        }
        Ok(a)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_tamper() {
        let key = [3u8; 32];
        let a = Ack {
            more: true,
            src: 0,
            dst: 2,
            acked_counter_lo: 41,
            snr_db: -19,
            dt_ds: 7,
        };
        let mut b = a.encode(&key, 41).unwrap();
        assert_eq!(b.len(), crate::JS8_FRAME_PAYLOAD_BYTES);
        assert_eq!(Ack::verify(&b, &key, 41).unwrap(), a);
        assert_eq!(Ack::verify(&b, &key, 41 + 256), Err(Error::AuthFailed));
        b[3] = 0;
        assert_eq!(Ack::verify(&b, &key, 41), Err(Error::AuthFailed));
    }
}
