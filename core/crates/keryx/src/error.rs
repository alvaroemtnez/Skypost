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
    #[error("counter {counter} from sender {src} is a replay or too old")]
    Replay { src: u8, counter: u16 },
    /// The exact frame was already delivered. The receiver should re-send its
    /// ACK (the sender probably missed the first one) and drop the frame.
    #[error("duplicate of an already delivered frame (sender {src}, counter {counter})")]
    Duplicate { src: u8, counter: u16 },
    #[error("counter jumped too far ahead")]
    CounterJump,
    #[error("sender counter exhausted; re-provision the group key")]
    CounterExhausted,
    #[error("frame was sent by this station")]
    FromSelf,
    #[error("malformed payload: {0}")]
    Malformed(&'static str),
    #[error("value out of range: {0}")]
    Range(&'static str),
    #[error("ARQ is busy with another message")]
    Busy,
}
