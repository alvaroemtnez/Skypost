//! # keryx — Keryx wire protocol, version 1
//!
//! Everything both ends (Android app and Pharos) must agree on lives in this
//! crate: identities and the keyring, the message header and bodies, the
//! Spanish text codec, ACK and poll frames, bridge addresses, `skypost:`
//! URIs, backups, segmentation into PHY frames, lane selection, the
//! stop-and-wait ARQ state machine and the default band schedule.
//!
//! The crate does no I/O. Radios, audio, storage and bridges belong to the
//! callers (`pharos`, the Android app via FFI, `keryx-cli`).
//!
//! Specs live in `docs/spec/`. If you change the wire format, update the spec
//! and the golden test vectors in `core/testvectors/` in the same commit.

pub mod access;
pub mod ack;
pub mod admin;
pub mod arq;
pub mod backup;
pub mod bandplan;
pub mod bits;
pub mod bridge;
pub mod canned;
pub mod crypto;
pub mod error;
pub mod header;
pub mod keyring;
pub mod lane;
pub mod message;
pub mod position;
pub mod segment;
pub mod textcodec;
pub mod uri;

pub use crypto::{GroupId, GroupKey, Identity, PublicKey};
pub use error::Error;
pub use header::{BROADCAST, Header, MsgType};
pub use keyring::{Card, Keyring, LinkRef, PeerKind};
pub use message::{Decoded, Message, Received};

/// Payload bytes carried by one JS8 frame when used as a raw binary PHY.
///
/// UNVERIFIED: JS8 frames are expected to carry 72 usable bits (FT8-derived
/// LDPC codeword, 87 info bits minus CRC and frame-type bits). Confirm against
/// the JS8Call-improved engine before freezing the wire format.
pub const JS8_FRAME_PAYLOAD_BYTES: usize = 9;
