use crate::crypto::PublicKey;
use crate::keyring::LinkRef;

/// Errors returned by the protocol layer.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Error {
    #[error("frame too short")]
    TooShort,
    #[error("unsupported protocol version {0}")]
    Version(u8),
    #[error("unknown message type {0}")]
    UnknownType(u8),
    #[error("authentication failed")]
    AuthFailed,
    /// No contact, Pharos or group matches the frame's short ids.
    #[error("no known station or group matches this frame")]
    UnknownSender,
    #[error("counter {counter} is older than the replay window")]
    Replay { counter: u16 },
    /// The exact frame was already delivered. The receiver should re-send its
    /// ACK (the sender probably missed the first one) and drop the frame.
    #[error("duplicate of an already delivered frame (counter {counter})")]
    Duplicate {
        link: LinkRef,
        sender: PublicKey,
        counter: u16,
    },
    #[error("send counter exhausted; this link needs a new identity or group key")]
    CounterExhausted,
    #[error("public key is weak (low order)")]
    WeakKey,
    #[error("no such contact, Pharos or group")]
    UnknownLink,
    #[error("the system random number generator failed")]
    Random,
    #[error("wrong passphrase or damaged backup")]
    Backup,
    #[error("malformed payload: {0}")]
    Malformed(&'static str),
    #[error("value out of range: {0}")]
    Range(&'static str),
    #[error("ARQ is busy with another message")]
    Busy,
}
