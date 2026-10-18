//! Application messages and their bodies (see `docs/spec/frame-format.md`).

use crate::crypto::Session;
use crate::header::{Header, MsgType};
use crate::position::{POSITION_LEN, Position};
use crate::segment::unpad_candidates;
use crate::{Error, canned, textcodec};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum Message {
    /// Free text, Spanish static-Huffman coded.
    Text(String),
    /// A stock phrase id from [`crate::canned::PHRASES`].
    Canned(u8),
    /// "Estoy bien", optionally with position.
    StatusOk(Option<Position>),
    /// Emergency, optionally with position.
    Sos(Option<Position>),
    Position(Position),
    /// "Anything queued for me?" Sent by a field station during a listen window.
    Poll,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Decoded {
    pub header: Header,
    pub message: Message,
}

impl Message {
    /// Text, or the matching stock phrase when there is one (saves bytes).
    pub fn from_text(s: &str) -> Self {
        match canned::lookup(s) {
            Some(id) => Self::Canned(id),
            None => Self::Text(s.to_owned()),
        }
    }

    pub fn msg_type(&self) -> MsgType {
        match self {
            Self::Text(_) => MsgType::Text,
            Self::Canned(_) => MsgType::Canned,
            Self::StatusOk(_) => MsgType::StatusOk,
            Self::Sos(_) => MsgType::Sos,
            Self::Position(_) => MsgType::Position,
            Self::Poll => MsgType::Poll,
        }
    }

    pub fn encode_body(&self) -> Result<Vec<u8>, Error> {
        Ok(match self {
            Self::Text(s) => textcodec::encode(s),
            Self::Canned(id) => {
                canned::phrase(*id).ok_or(Error::Range("unknown phrase id"))?;
                vec![*id]
            }
            Self::StatusOk(p) | Self::Sos(p) => match p {
                Some(p) => p.encode()?.to_vec(),
                None => vec![],
            },
            Self::Position(p) => p.encode()?.to_vec(),
            Self::Poll => vec![],
        })
    }

    pub fn decode_body(t: MsgType, b: &[u8]) -> Result<Self, Error> {
        let opt_pos = |b: &[u8]| -> Result<Option<Position>, Error> {
            match b.len() {
                0 => Ok(None),
                POSITION_LEN => Ok(Some(Position::decode(b)?)),
                _ => Err(Error::Malformed("status body must be empty or a position")),
            }
        };
        Ok(match t {
            MsgType::Text => Self::Text(textcodec::decode(b)?),
            MsgType::Canned => {
                let [id] = b else {
                    return Err(Error::Malformed("canned body must be 1 byte"));
                };
                canned::phrase(*id).ok_or(Error::Range("unknown phrase id"))?;
                Self::Canned(*id)
            }
            MsgType::StatusOk => Self::StatusOk(opt_pos(b)?),
            MsgType::Sos => Self::Sos(opt_pos(b)?),
            MsgType::Position => Self::Position(Position::decode(b)?),
            MsgType::Poll => {
                if !b.is_empty() {
                    return Err(Error::Malformed("poll body must be empty"));
                }
                Self::Poll
            }
            MsgType::Ack => return Err(Error::Malformed("ACKs are decoded with ack::Ack")),
        })
    }

    /// Human-readable rendering for bridges and logs.
    pub fn render(&self) -> String {
        let pos = |p: &Option<Position>| {
            p.map(|p| format!(" ({:.5}, {:.5})", p.lat, p.lon))
                .unwrap_or_default()
        };
        match self {
            Self::Text(s) => s.clone(),
            Self::Canned(id) => canned::phrase(*id).unwrap_or("?").to_owned(),
            Self::StatusOk(p) => format!("Estoy bien{}", pos(p)),
            Self::Sos(p) => format!("SOS{}", pos(p)),
            Self::Position(p) => format!("Posición ({:.5}, {:.5})", p.lat, p.lon),
            Self::Poll => "(consulta de mensajes)".to_owned(),
        }
    }
}

/// Seal a message into a wire frame (header + body + tag).
pub fn encode(
    session: &mut Session,
    msg: &Message,
    dst: u8,
    encrypted: bool,
) -> Result<Vec<u8>, Error> {
    let body = msg.encode_body()?;
    let header = Header {
        encrypted,
        msg_type: msg.msg_type(),
        src: session.me,
        dst,
        counter: 0,
    };
    session.seal(header, &body)
}

/// Verify and decode an unpadded wire frame.
pub fn decode(session: &mut Session, frame: &[u8]) -> Result<Decoded, Error> {
    let (header, body) = session.open(frame)?;
    let message = Message::decode_body(header.msg_type, &body)?;
    Ok(Decoded { header, message })
}

/// Decode reassembled PHY frames that may carry segment padding: tries the
/// raw and the unpadded length and keeps whichever authenticates.
pub fn decode_padded(session: &mut Session, data: &[u8]) -> Result<Decoded, Error> {
    let mut last = Error::TooShort;
    for cand in unpad_candidates(data).into_iter().rev() {
        match decode(session, cand) {
            Ok(d) => return Ok(d),
            // The tag matched a delivered frame: that verdict is final.
            Err(e @ Error::Duplicate { .. }) => return Err(e),
            Err(e) => last = e,
        }
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::GroupKey;
    use crate::segment::{reassemble, segment};
    use crate::{BROADCAST, JS8_FRAME_PAYLOAD_BYTES as MTU};

    fn pair() -> (Session, Session) {
        let gk = GroupKey([9u8; 32]);
        (
            Session::provision(&gk, 2, 8).unwrap(),
            Session::provision(&gk, 0, 8).unwrap(),
        )
    }

    #[test]
    fn all_types_roundtrip_through_segmentation() {
        let (mut field, mut base) = pair();
        let p = Position {
            lat: 42.6,
            lon: 0.65,
        };
        let msgs = [
            Message::from_text("Llegamos al refugio, todo bien. Mañana bajamos por el valle."),
            Message::from_text("Estoy bien"),
            Message::StatusOk(Some(p)),
            Message::Sos(None),
            Message::Position(p),
            Message::Poll,
        ];
        for (i, m) in msgs.iter().enumerate() {
            let wire = encode(&mut field, m, BROADCAST, i % 2 == 0).unwrap();
            let rx = reassemble(&segment(&wire, MTU));
            let d = decode_padded(&mut base, &rx).unwrap();
            match (&d.message, m) {
                (Message::StatusOk(Some(a)), Message::StatusOk(Some(b)))
                | (Message::Position(a), Message::Position(b)) => {
                    assert!((a.lat - b.lat).abs() < 1e-4 && (a.lon - b.lon).abs() < 1e-4)
                }
                _ => assert_eq!(&d.message, m),
            }
        }
    }

    #[test]
    fn padded_duplicate_is_reported_as_duplicate() {
        let (mut field, mut base) = pair();
        let wire = encode(&mut field, &Message::Canned(7), BROADCAST, false).unwrap();
        let rx = reassemble(&segment(&wire, MTU));
        assert_ne!(rx.len(), wire.len(), "test needs a padded frame");
        decode_padded(&mut base, &rx).unwrap();
        assert!(matches!(
            decode_padded(&mut base, &rx),
            Err(Error::Duplicate { src: 2, counter: 0 })
        ));
    }

    #[test]
    fn airtime_budget_matches_design_doc() {
        let (mut field, _) = pair();
        let ok = encode(
            &mut field,
            &Message::StatusOk(Some(Position {
                lat: 40.0,
                lon: -3.0,
            })),
            BROADCAST,
            true,
        )
        .unwrap();
        assert!(
            ok.len() <= 2 * MTU,
            "status+position should fit 2 JS8 frames, got {}",
            ok.len()
        );
        let text = encode(
            &mut field,
            &Message::from_text("Llegamos al refugio, todo bien. Mañana bajamos por el valle."),
            BROADCAST,
            true,
        )
        .unwrap();
        // 4 header + 37 body + 8 tag = 49 bytes → 6 JS8 frames.
        assert!(
            text.len() <= 6 * MTU,
            "60-char text should fit 6 JS8 frames, got {}",
            text.len()
        );
    }
}
