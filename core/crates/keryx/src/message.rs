//! Application messages, their bodies, and sealing/opening whole frames
//! (see `docs/spec/frame-format.md`).

use crate::access::PROOF_LEN;
use crate::bridge::{Address, notice};
use crate::crypto::PublicKey;
use crate::header::{BROADCAST, HEADER_LEN, Header, MsgType, peek_type};
use crate::keyring::{Card, Keyring, LinkRef, PeerKind, Sealed};
use crate::position::{POSITION_LEN, Position};
use crate::segment::unpad_candidates;
use crate::{Error, admin, canned, textcodec};
use serde::{Deserialize, Serialize};

/// `Announce` flag bits.
pub mod flags {
    pub const PHAROS: u8 = 1 << 0;
    /// It accepts an access code ([`crate::access`]): a valid proof admits at once.
    pub const CODE_REQUIRED: u8 = 1 << 1;
    pub const SIGNAL: u8 = 1 << 2;
    pub const WHATSAPP: u8 = 1 << 3;
    pub const MESHCORE: u8 = 1 << 4;
    /// Closed: registered users and accepted contacts only.
    pub const CLOSED: u8 = 1 << 5;
    /// Closed, but it takes access requests (decision 0021).
    pub const REQUESTS: u8 = 1 << 6;
}

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
    /// Ping / "anything queued for me?", and how long the sender keeps listening.
    Poll {
        listen_min: u8,
    },
    /// Ask a Pharos to register this station's key under `alias`. `proof`
    /// is [`crate::access::AccessKey::proof`], or all zeros without a code.
    Register {
        key: PublicKey,
        proof: [u8; PROOF_LEN],
        alias: String,
    },
    /// Introduce this station's key to a group.
    GroupJoin {
        key: PublicKey,
        alias: String,
    },
    /// Bind `handle` to a bridged contact; `name` is a display hint.
    BridgeOpen {
        handle: u8,
        address: Address,
        name: Option<String>,
    },
    /// A message to or from the bridged contact bound to `handle`.
    Bridged {
        handle: u8,
        inner: Box<Message>,
    },
    /// A Pharos status code ([`crate::bridge::notice`]).
    Notice {
        code: u8,
        handle: Option<u8>,
    },
    /// Admin command to a Pharos.
    Admin(admin::Command),
    /// Admin reply or notice from a Pharos.
    AdminReply(admin::Reply),
}

/// An authenticated message, opened and decoded.
#[derive(Clone, Debug, PartialEq)]
pub struct Decoded {
    pub header: Header,
    pub link: LinkRef,
    pub sender: PublicKey,
    pub message: Message,
    /// See [`crate::keyring::Opened::new_peer`].
    pub new_peer: bool,
}

/// Anything [`decode`] can return.
#[derive(Clone, Debug, PartialEq)]
pub enum Received {
    Message(Decoded),
    /// Someone asks which Pharos stations are in range.
    Discover {
        src: u8,
        query: u16,
    },
    /// Unauthenticated identity, normally a Pharos answering a `Discover`.
    Announce {
        dst: u8,
        query: u16,
        card: Card,
        flags: u8,
    },
}

fn text_with_alias(key: &PublicKey, alias: &str) -> Vec<u8> {
    [key.0.as_slice(), &textcodec::encode(alias)].concat()
}

fn split_key(b: &[u8]) -> Result<(PublicKey, &[u8]), Error> {
    let k = b.get(..32).ok_or(Error::TooShort)?;
    Ok((PublicKey(k.try_into().unwrap()), &b[32..]))
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
            Self::Poll { .. } => MsgType::Poll,
            Self::Register { .. } => MsgType::Register,
            Self::GroupJoin { .. } => MsgType::GroupJoin,
            Self::BridgeOpen { .. } => MsgType::BridgeOpen,
            Self::Bridged { .. } => MsgType::Bridged,
            Self::Notice { .. } => MsgType::Notice,
            Self::Admin(_) => MsgType::Admin,
            Self::AdminReply(_) => MsgType::AdminReply,
        }
    }

    /// Types that may ride inside `Bridged`.
    pub fn is_content(&self) -> bool {
        matches!(
            self,
            Self::Text(_) | Self::Canned(_) | Self::StatusOk(_) | Self::Sos(_) | Self::Position(_)
        )
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
            Self::Poll { listen_min } => vec![*listen_min],
            Self::Register { key, proof, alias } => {
                [key.0.as_slice(), proof, &textcodec::encode(alias)].concat()
            }
            Self::GroupJoin { key, alias } => text_with_alias(key, alias),
            Self::BridgeOpen {
                handle,
                address,
                name,
            } => {
                let mut b = vec![*handle];
                address.encode(&mut b)?;
                if let Some(n) = name.as_deref().filter(|n| !n.is_empty()) {
                    b.extend(textcodec::encode(n));
                }
                b
            }
            Self::Bridged { handle, inner } => {
                if !inner.is_content() {
                    return Err(Error::Range(
                        "only text, phrases, status, SOS and positions are bridged",
                    ));
                }
                let mut b = vec![*handle, inner.msg_type() as u8];
                b.extend(inner.encode_body()?);
                b
            }
            Self::Notice { code, handle } => {
                let mut b = vec![*code];
                match (notice::has_handle(*code), handle) {
                    (true, Some(h)) => b.push(*h),
                    (false, None) => {}
                    _ => return Err(Error::Range("notice handle does not match its code")),
                }
                b
            }
            Self::Admin(c) => c.encode()?,
            Self::AdminReply(r) => r.encode()?,
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
                let [listen_min] = b else {
                    return Err(Error::Malformed("poll body must be 1 byte"));
                };
                Self::Poll {
                    listen_min: *listen_min,
                }
            }
            MsgType::Register => {
                let (key, rest) = split_key(b)?;
                let proof = rest.get(..PROOF_LEN).ok_or(Error::TooShort)?;
                Self::Register {
                    key,
                    proof: proof.try_into().unwrap(),
                    alias: textcodec::decode(&rest[PROOF_LEN..])?,
                }
            }
            MsgType::GroupJoin => {
                let (key, rest) = split_key(b)?;
                Self::GroupJoin {
                    key,
                    alias: textcodec::decode(rest)?,
                }
            }
            MsgType::BridgeOpen => {
                let (&handle, rest) = b.split_first().ok_or(Error::TooShort)?;
                let (address, n) = Address::decode(rest)?;
                let rest = &rest[n..];
                let name = if rest.is_empty() {
                    None
                } else {
                    Some(textcodec::decode(rest)?)
                };
                Self::BridgeOpen {
                    handle,
                    address,
                    name,
                }
            }
            MsgType::Bridged => {
                let [handle, t, rest @ ..] = b else {
                    return Err(Error::TooShort);
                };
                let inner = Self::decode_body(MsgType::try_from(*t)?, rest)?;
                if !inner.is_content() {
                    return Err(Error::Malformed("bridged type"));
                }
                Self::Bridged {
                    handle: *handle,
                    inner: Box::new(inner),
                }
            }
            MsgType::Notice => {
                let (&code, rest) = b.split_first().ok_or(Error::TooShort)?;
                let handle = match (notice::has_handle(code), rest) {
                    (true, [h]) => Some(*h),
                    (false, []) => None,
                    _ => return Err(Error::Malformed("notice length")),
                };
                Self::Notice { code, handle }
            }
            MsgType::Admin => Self::Admin(admin::Command::decode(b)?),
            MsgType::AdminReply => Self::AdminReply(admin::Reply::decode(b)?),
            MsgType::Ack | MsgType::Discover | MsgType::Announce => {
                return Err(Error::Malformed("not a sealed message type"));
            }
        })
    }

    /// Human-readable rendering for bridges and logs (Spanish).
    pub fn render(&self) -> String {
        let pos =
            |p: &Option<Position>| p.map(|p| format!(" {}", p.map_link())).unwrap_or_default();
        match self {
            Self::Text(s) => s.clone(),
            Self::Canned(id) => canned::phrase(*id).unwrap_or("?").to_owned(),
            Self::StatusOk(p) => format!("Estoy bien{}", pos(p)),
            Self::Sos(p) => format!("🆘 SOS{}", pos(p)),
            Self::Position(p) => format!("Posición {}", p.map_link()),
            Self::Poll { listen_min } => format!("(consulta; a la escucha {listen_min} min)"),
            Self::Register { alias, .. } => format!("(registro de @{alias})"),
            Self::GroupJoin { alias, .. } => format!("(@{alias} se une al grupo)"),
            Self::BridgeOpen {
                handle, address, ..
            } => format!("(chat {handle} con {})", address.display()),
            Self::Bridged { handle, inner } => format!("[{handle}] {}", inner.render()),
            Self::Notice { code, handle } => format!("(aviso {code} {handle:?})"),
            // Never the password.
            Self::Admin(admin::Command::Login(_)) => "(admin login)".to_owned(),
            Self::Admin(c) => format!("(admin {c:?})"),
            Self::AdminReply(r) => format!("(admin reply {r:?})"),
        }
    }
}

/// Seal a message for `link`. Poll goes out compact, and `Register` and
/// `GroupJoin` always go signed (the receiver needs the key in clear to find
/// the link); `encrypted` is ignored for them. Persist the keyring before
/// transmitting the result.
pub fn encode(
    keyring: &mut Keyring,
    msg: &Message,
    link: &LinkRef,
    encrypted: bool,
) -> Result<Sealed, Error> {
    match (msg, link) {
        (Message::Poll { listen_min }, LinkRef::Peer(k)) => {
            return keyring.seal_poll(k, *listen_min);
        }
        (Message::Poll { .. }, _) => return Err(Error::Range("polls go to one peer")),
        (Message::Register { key, .. }, LinkRef::Peer(_))
        | (Message::GroupJoin { key, .. }, LinkRef::Group(_))
            if *key == keyring.public() => {}
        (Message::Register { .. } | Message::GroupJoin { .. }, _) => {
            return Err(Error::Range(
                "register/join carry this station's key on the right link",
            ));
        }
        _ => {}
    }
    let encrypted =
        encrypted && !matches!(msg, Message::Register { .. } | Message::GroupJoin { .. });
    keyring.seal(link, msg.msg_type(), encrypted, &msg.encode_body()?)
}

/// Unauthenticated `Discover` with a random query id.
pub fn encode_discover(keyring: &Keyring, query: u16) -> Result<Vec<u8>, Error> {
    let h = Header {
        encrypted: false,
        msg_type: MsgType::Discover,
        src: keyring.short_id(),
        dst: BROADCAST,
        counter: query,
    };
    Ok(h.encode()?.to_vec())
}

/// Unauthenticated `Announce` of this station's card, answering `query`.
pub fn encode_announce(
    keyring: &Keyring,
    flags: u8,
    dst: u8,
    query: u16,
) -> Result<Vec<u8>, Error> {
    let card = keyring.card();
    let h = Header {
        encrypted: false,
        msg_type: MsgType::Announce,
        src: keyring.short_id(),
        dst,
        counter: query,
    };
    let name = match &card.callsign {
        Some(c) => format!("{} {c}", card.alias),
        None => card.alias.clone(),
    };
    let mut b = h.encode()?.to_vec();
    b.extend_from_slice(&card.key.0);
    b.push(flags);
    b.extend(textcodec::encode(&name));
    Ok(b)
}

fn decode_unauth(data: &[u8]) -> Result<Received, Error> {
    let h = Header::decode(data)?;
    let body = &data[HEADER_LEN..];
    match h.msg_type {
        MsgType::Discover if body.is_empty() => Ok(Received::Discover {
            src: h.src,
            query: h.counter,
        }),
        MsgType::Announce => {
            let (key, rest) = split_key(body)?;
            let (&flags, rest) = rest.split_first().ok_or(Error::TooShort)?;
            let name = textcodec::decode(rest)?;
            let (alias, callsign) = match name.split_once(' ') {
                Some((a, c)) => (a.to_owned(), Some(c.to_owned())),
                None => (name, None),
            };
            if key.short_id() != h.src {
                return Err(Error::Malformed("announce key does not match src"));
            }
            let kind = if flags & flags::PHAROS != 0 {
                PeerKind::Pharos
            } else {
                PeerKind::Person
            };
            Ok(Received::Announce {
                dst: h.dst,
                query: h.counter,
                card: Card {
                    key,
                    alias,
                    callsign,
                    kind,
                },
                flags,
            })
        }
        _ => Err(Error::Malformed("unauthenticated frame")),
    }
}

/// Decode reassembled PHY data that may carry segment padding: tries the
/// unpadded and the raw length and keeps whichever parses (unauthenticated
/// frames) or authenticates (everything else). ACKs go to
/// [`Keyring::open_ack`] instead.
pub fn decode(keyring: &mut Keyring, data: &[u8]) -> Result<Received, Error> {
    let t = peek_type(data)?;
    if t == MsgType::Ack {
        return Err(Error::Malformed("ACKs are opened with Keyring::open_ack"));
    }
    let mut last = Error::TooShort;
    for cand in unpad_candidates(data).into_iter().rev() {
        if t.is_unauthenticated() {
            match decode_unauth(cand) {
                Ok(r) => return Ok(r),
                Err(e) => last = e,
            }
            continue;
        }
        match keyring.open(cand) {
            Ok(o) => {
                let message = Message::decode_body(o.header.msg_type, &o.body)?;
                return Ok(Received::Message(Decoded {
                    header: o.header,
                    link: o.link,
                    sender: o.sender,
                    message,
                    new_peer: o.new_peer,
                }));
            }
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
    use crate::crypto::{GroupKey, Identity};
    use crate::segment::{reassemble, segment};
    use crate::{JS8_FRAME_PAYLOAD_BYTES as MTU, keyring::Keyring};

    fn pair() -> (Keyring, Keyring) {
        let mut field = Keyring::new(
            Identity::from_secret([2; 32]),
            "ana",
            Some("EA5AAA"),
            PeerKind::Person,
        );
        let mut base = Keyring::new(
            Identity::from_secret([3; 32]),
            "casa",
            Some("EA5YYY"),
            PeerKind::Pharos,
        );
        field.add_peer(base.card().clone(), true).unwrap();
        base.add_peer(field.card().clone(), true).unwrap();
        (field, base)
    }

    fn over_air(wire: &[u8]) -> Vec<u8> {
        reassemble(&segment(wire, MTU))
    }

    fn msg(r: Received) -> Decoded {
        match r {
            Received::Message(d) => d,
            other => panic!("{other:?}"),
        }
    }

    fn close(a: &Message, b: &Message) -> bool {
        let near = |a: &Position, b: &Position| {
            (a.lat - b.lat).abs() < 1e-4 && (a.lon - b.lon).abs() < 1e-4
        };
        match (a, b) {
            (Message::StatusOk(Some(a)), Message::StatusOk(Some(b)))
            | (Message::Position(a), Message::Position(b)) => near(a, b),
            (
                Message::Bridged {
                    handle: h1,
                    inner: a,
                },
                Message::Bridged {
                    handle: h2,
                    inner: b,
                },
            ) => h1 == h2 && close(a, b),
            _ => a == b,
        }
    }

    #[test]
    fn all_types_roundtrip_through_segmentation() {
        let (mut field, mut base) = pair();
        let link = LinkRef::Peer(base.public());
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
            Message::Poll { listen_min: 20 },
            Message::Register {
                key: field.public(),
                proof: crate::access::NO_PROOF,
                alias: "ana".into(),
            },
            Message::BridgeOpen {
                handle: 3,
                address: Address::WhatsappPhone("34600123456".into()),
                name: Some("Mamá".into()),
            },
            Message::Bridged {
                handle: 3,
                inner: Box::new(Message::StatusOk(Some(p))),
            },
            Message::Notice {
                code: notice::RATE_LIMITED,
                handle: Some(3),
            },
        ];
        for (i, m) in msgs.iter().enumerate() {
            let s = encode(&mut field, m, &link, i % 2 == 0).unwrap();
            let d = msg(decode(&mut base, &over_air(&s.wire)).unwrap());
            assert!(close(&d.message, m), "{:?} != {m:?}", d.message);
            assert_eq!(d.sender, field.public());
        }
    }

    #[test]
    fn discover_and_announce() {
        let (mut field, mut base) = pair();
        let q = encode_discover(&field, 0xBEEF).unwrap();
        assert_eq!(q.len(), 4);
        let Received::Discover { src, query } = decode(&mut base, &over_air(&q)).unwrap() else {
            panic!()
        };
        assert_eq!((src, query), (field.short_id(), 0xBEEF));
        let flags = flags::PHAROS | flags::SIGNAL;
        let a = encode_announce(&base, flags, src, query).unwrap();
        let Received::Announce {
            card,
            flags: f,
            query,
            ..
        } = decode(&mut field, &over_air(&a)).unwrap()
        else {
            panic!()
        };
        assert_eq!((card, f, query), (base.card().clone(), flags, 0xBEEF));
    }

    #[test]
    fn group_join_then_group_text() {
        let gk = GroupKey([6; 32]);
        let (mut a, mut b) = pair();
        let id = a.add_group(gk.clone(), "Familia");
        b.add_group(gk, "Familia");
        let g = LinkRef::Group(id);
        let join = Message::GroupJoin {
            key: a.public(),
            alias: "ana".into(),
        };
        let s = encode(&mut a, &join, &g, false).unwrap();
        assert!(close(
            &msg(decode(&mut b, &over_air(&s.wire)).unwrap()).message,
            &join
        ));
        let s = encode(&mut a, &Message::from_text("A cenar"), &g, true).unwrap();
        let d = msg(decode(&mut b, &over_air(&s.wire)).unwrap());
        assert_eq!((d.link, d.message), (g, Message::Text("A cenar".into())));
    }

    #[test]
    fn padded_duplicate_is_reported_as_duplicate() {
        let (mut field, mut base) = pair();
        let s = encode(
            &mut field,
            &Message::Canned(7),
            &LinkRef::Peer(base.public()),
            false,
        )
        .unwrap();
        let rx = over_air(&s.wire);
        assert_ne!(rx.len(), s.wire.len(), "test needs a padded frame");
        decode(&mut base, &rx).unwrap();
        assert!(matches!(
            decode(&mut base, &rx),
            Err(Error::Duplicate { counter: 0, .. })
        ));
    }

    #[test]
    fn airtime_budget_matches_spec() {
        let (mut field, base) = pair();
        let link = LinkRef::Peer(base.public());
        let frames = |m: &Message, f: &mut Keyring| {
            encode(f, m, &link, true).unwrap().wire.len().div_ceil(MTU)
        };
        let pos = Position {
            lat: 40.0,
            lon: -3.0,
        };
        assert_eq!(frames(&Message::StatusOk(Some(pos)), &mut field), 2);
        assert_eq!(frames(&Message::Poll { listen_min: 10 }, &mut field), 1);
        assert_eq!(
            frames(
                &Message::from_text("Llegamos al refugio, todo bien. Mañana bajamos por el valle."),
                &mut field
            ),
            6
        );
        let open = Message::BridgeOpen {
            handle: 0,
            address: Address::SignalPhone("34600123456".into()),
            name: None,
        };
        assert_eq!(frames(&open, &mut field), 3);
        let bridged = Message::Bridged {
            handle: 0,
            inner: Box::new(Message::Canned(0)),
        };
        assert_eq!(frames(&bridged, &mut field), 2);
        // "casa EA5YYY": upper case costs SHIFT codes, so 6 frames.
        assert_eq!(
            encode_announce(&base, flags::PHAROS, 0, 0)
                .unwrap()
                .len()
                .div_ceil(MTU),
            6
        );
        let reg = Message::Register {
            key: field.public(),
            proof: crate::access::NO_PROOF,
            alias: "ana".into(),
        };
        assert_eq!(frames(&reg, &mut field), 6);
    }
}
