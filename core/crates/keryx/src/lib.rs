//! # keryx — Keryx wire protocol, version 0
//!
//! Everything both ends (Android app and base daemon) must agree on lives in
//! this crate: the message header, the Spanish text codec, the crypto
//! ratchet, ACK frames, segmentation into PHY frames, lane selection, the
//! stop-and-wait ARQ state machine and the default band schedule.
//!
//! The crate does no I/O. Radios, audio, storage and bridges belong to the
//! callers (`pharos`, the Android app via FFI, `keryx-cli`).
//!
//! Specs live in `docs/spec/`. If you change the wire format, update the spec
//! and the golden test vectors in `core/testvectors/` in the same commit.

pub mod ack;
pub mod arq;
pub mod bandplan;
pub mod bits;
pub mod canned;
pub mod crypto;
pub mod error;
pub mod header;
pub mod lane;
pub mod message;
pub mod position;
pub mod segment;
pub mod textcodec;

pub use error::Error;
pub use header::{BROADCAST, Header, MsgType};
pub use message::{Decoded, Message};

/// Payload bytes carried by one JS8 frame when used as a raw binary PHY.
///
/// UNVERIFIED: JS8 frames are expected to carry 72 usable bits (FT8-derived
/// LDPC codeword, 87 info bits minus CRC and frame-type bits). Confirm against
/// the JS8Call-improved engine before freezing the wire format.
pub const JS8_FRAME_PAYLOAD_BYTES: usize = 9;
