//! The 4-byte message header (see `docs/spec/frame-format.md`).
//!
//! ```text
//! byte 0: version (2 bits) | encrypted (1 bit) | type (5 bits)
//! byte 1: src short id (4 bits) | dst short id (4 bits)
//! byte 2-3: counter (u16, big endian)
//! ```
//!
//! Short ids are hashes of public keys (or group keys), so several stations
//! can share one; the tag decides which candidate key a frame belongs to.

use crate::Error;
use serde::{Deserialize, Serialize};

pub const VERSION: u8 = 1;
pub const HEADER_LEN: usize = 4;
/// Destination id meaning "anyone listening".
pub const BROADCAST: u8 = 0x0F;
/// Highest short id. 0..=14 name stations and groups, 15 is [`BROADCAST`].
pub const MAX_SHORT_ID: u8 = 14;

/// Message types. Values are part of the wire format: append only.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum MsgType {
    Text = 1,
    Canned = 2,
    StatusOk = 3,
    Sos = 4,
    Position = 5,
    /// Compact 9-byte frame (ping / "anything for me?"), see [`crate::keyring`].
    Poll = 6,
    /// Compact ACK frame, see [`crate::ack`]. Uses a different layout.
    Ack = 7,
    /// Unauthenticated "any Pharos out there?".
    Discover = 8,
    /// Unauthenticated identity answer to a `Discover`.
    Announce = 9,
    /// A client asks a Pharos to register its key and alias.
    Register = 10,
    /// A new member introduces its key to a group.
    GroupJoin = 11,
    /// Binds a bridge handle to a Signal/WhatsApp/MeshCore address.
    BridgeOpen = 12,
    /// A message to or from a bridged contact.
    Bridged = 13,
    /// Status codes from a Pharos (registration, bridge errors).
    Notice = 14,
    /// A field user's admin command to a Pharos ([`crate::admin::Command`]).
    Admin = 15,
    /// A Pharos's admin reply or notice ([`crate::admin::Reply`]).
    AdminReply = 16,
}

impl MsgType {
    /// Types sent without a tag.
    pub fn is_unauthenticated(self) -> bool {
        matches!(self, Self::Discover | Self::Announce)
    }
}

impl TryFrom<u8> for MsgType {
    type Error = Error;
    fn try_from(v: u8) -> Result<Self, Error> {
        Ok(match v {
            1 => Self::Text,
            2 => Self::Canned,
            3 => Self::StatusOk,
            4 => Self::Sos,
            5 => Self::Position,
            6 => Self::Poll,
            7 => Self::Ack,
            8 => Self::Discover,
            9 => Self::Announce,
            10 => Self::Register,
            11 => Self::GroupJoin,
            12 => Self::BridgeOpen,
            13 => Self::Bridged,
            14 => Self::Notice,
            15 => Self::Admin,
            16 => Self::AdminReply,
            other => return Err(Error::UnknownType(other)),
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Header {
    pub encrypted: bool,
    pub msg_type: MsgType,
    pub src: u8,
    pub dst: u8,
    pub counter: u16,
}

impl Header {
    pub fn encode(&self) -> Result<[u8; HEADER_LEN], Error> {
        if self.src > MAX_SHORT_ID {
            return Err(Error::Range("src id must be 0..=14"));
        }
        if self.dst > BROADCAST {
            return Err(Error::Range("dst id must be 0..=15"));
        }
        let b0 = (VERSION << 6) | (u8::from(self.encrypted) << 5) | (self.msg_type as u8 & 0x1F);
        let b1 = (self.src << 4) | self.dst;
        let c = self.counter.to_be_bytes();
        Ok([b0, b1, c[0], c[1]])
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, Error> {
        if bytes.len() < HEADER_LEN {
            return Err(Error::TooShort);
        }
        let msg_type = peek_type(bytes)?;
        Ok(Self {
            encrypted: bytes[0] & 0x20 != 0,
            msg_type,
            src: bytes[1] >> 4,
            dst: bytes[1] & 0x0F,
            counter: u16::from_be_bytes([bytes[2], bytes[3]]),
        })
    }
}

/// Read version and type from byte 0 without parsing the rest, so callers can
/// dispatch between normal messages and compact ACKs.
pub fn peek_type(bytes: &[u8]) -> Result<MsgType, Error> {
    let b0 = *bytes.first().ok_or(Error::TooShort)?;
    let version = b0 >> 6;
    if version != VERSION {
        return Err(Error::Version(version));
    }
    MsgType::try_from(b0 & 0x1F)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let h = Header {
            encrypted: true,
            msg_type: MsgType::Sos,
            src: 3,
            dst: BROADCAST,
            counter: 0xBEEF,
        };
        let b = h.encode().unwrap();
        assert_eq!(b, [0b0110_0100, 0x3F, 0xBE, 0xEF]);
        assert_eq!(Header::decode(&b).unwrap(), h);
    }

    #[test]
    fn rejects_bad_ids_and_versions() {
        let h = Header {
            encrypted: false,
            msg_type: MsgType::Text,
            src: 15,
            dst: 0,
            counter: 0,
        };
        assert!(h.encode().is_err());
        assert_eq!(
            Header::decode(&[0b0000_0001, 0, 0, 0]),
            Err(Error::Version(0))
        );
        assert_eq!(
            Header::decode(&[0b0100_0000, 0, 0, 0]),
            Err(Error::UnknownType(0))
        );
        assert_eq!(
            Header::decode(&[0b0101_1111, 0, 0, 0]),
            Err(Error::UnknownType(31))
        );
    }
}
