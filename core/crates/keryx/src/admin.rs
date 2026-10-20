//! Binary admin messages between a field user and a Pharos (decision 0021,
//! `docs/spec/frame-format.md`, "Admin and AdminReply"). One opcode byte and
//! compact arguments: a switch change is a single byte. Replies are
//! structured, so each app shows them in its own language.

use crate::Error;
use crate::bridge::{Address, network};
use crate::crypto::PublicKey;
use crate::textcodec;
use serde::{Deserialize, Serialize};

/// The Pharos access switches.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Switch {
    /// Anyone may use it; off = closed.
    Open = 0,
    /// While closed, unknown users and contacts may ask to join.
    Requests = 1,
    /// The admin password works.
    Login = 2,
    /// Encrypted only.
    Emergency = 3,
    /// No discovery answers, no automatic messages.
    Stealth = 4,
}

impl Switch {
    pub const ALL: [Switch; 5] = [
        Self::Open,
        Self::Requests,
        Self::Login,
        Self::Emergency,
        Self::Stealth,
    ];

    fn from_u8(v: u8) -> Result<Self, Error> {
        Self::ALL
            .get(usize::from(v))
            .copied()
            .ok_or(Error::Malformed("unknown switch"))
    }
}

/// All switches as one byte, bit = [`Switch`] value.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Switches(pub u8);

impl Switches {
    pub fn get(self, s: Switch) -> bool {
        self.0 & (1 << s as u8) != 0
    }

    pub fn set(&mut self, s: Switch, on: bool) {
        if on {
            self.0 |= 1 << s as u8;
        } else {
            self.0 &= !(1 << s as u8);
        }
    }
}

/// Short id of a person or node on air: 8 bytes, too many to forge a match
/// (2^64 tries), few enough to keep commands short. Never an alias: aliases
/// are chosen by their owners, so anyone can claim one.
pub type Id = [u8; 8];

/// A field user's id: the first 8 bytes of its key fingerprint, i.e. the
/// start of its safety code ("4F2A 9C11 …").
pub fn user_id(key: &PublicKey) -> Id {
    key.fingerprint()[..8].try_into().unwrap()
}

/// A MeshCore contact's id: the first 8 bytes of its public key.
pub fn meshcore_id(key: &[u8; 32]) -> Id {
    key[..8].try_into().unwrap()
}

/// Who a command is about.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Target {
    /// A field user, by [`user_id`].
    User(Id),
    /// A Signal or WhatsApp contact, in full (the number is the identity).
    Contact(Address),
    /// A MeshCore contact the Pharos knows, by [`meshcore_id`] (a full key
    /// is 32 bytes on air).
    Meshcore(Id),
}

const TARGET_USER: u8 = 0x00;
const TARGET_MESHCORE: u8 = 0x10;

impl Target {
    fn encode(&self, out: &mut Vec<u8>) -> Result<(), Error> {
        match self {
            Self::User(id) => {
                out.push(TARGET_USER);
                out.extend_from_slice(id);
            }
            Self::Contact(Address::MeshcoreContact(k)) => {
                out.push(TARGET_MESHCORE);
                out.extend_from_slice(&meshcore_id(k));
            }
            Self::Contact(c) => c.encode(out)?,
            Self::Meshcore(id) => {
                out.push(TARGET_MESHCORE);
                out.extend_from_slice(id);
            }
        }
        Ok(())
    }

    fn decode(b: &[u8]) -> Result<Self, Error> {
        let id = |rest: &[u8]| -> Result<Id, Error> {
            rest.try_into()
                .map_err(|_| Error::Malformed("an id is 8 bytes"))
        };
        match b.split_first().ok_or(Error::TooShort)? {
            (&TARGET_USER, rest) => Ok(Self::User(id(rest)?)),
            (&TARGET_MESHCORE, rest) => Ok(Self::Meshcore(id(rest)?)),
            _ => {
                let (c, n) = Address::decode(b)?;
                if n != b.len() || matches!(c, Address::MeshcoreContact(_)) {
                    return Err(Error::Malformed("one Signal or WhatsApp address"));
                }
                Ok(Self::Contact(c))
            }
        }
    }
}

/// A field user's command to a Pharos.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Command {
    Status,
    /// List pending access requests.
    Requests,
    Accept(u8),
    Refuse(u8),
    Clients,
    Admins,
    Block(Target),
    Unblock(Target),
    /// Let a Signal or WhatsApp contact write (MeshCore only by request).
    Allow(Address),
    AddAdmin(Target),
    RemoveAdmin(Target),
    Set(Switch, bool),
    /// The admin password; accepted only in an encrypted frame.
    Login(String),
}

/// Opcodes. Append only. `Set` packs into the opcode byte: `0x40 | switch << 1 | on`.
mod op {
    pub const STATUS: u8 = 0;
    pub const REQUESTS: u8 = 1;
    pub const ACCEPT: u8 = 2;
    pub const REFUSE: u8 = 3;
    pub const CLIENTS: u8 = 4;
    pub const ADMINS: u8 = 5;
    pub const BLOCK: u8 = 6;
    pub const UNBLOCK: u8 = 7;
    pub const ALLOW: u8 = 8;
    pub const ADD_ADMIN: u8 = 9;
    pub const REMOVE_ADMIN: u8 = 10;
    pub const LOGIN: u8 = 11;
    pub const SET: u8 = 0x40;
}

impl Command {
    /// The opcode byte (also echoed in [`Reply::Done`]).
    pub fn opcode(&self) -> u8 {
        match self {
            Self::Status => op::STATUS,
            Self::Requests => op::REQUESTS,
            Self::Accept(_) => op::ACCEPT,
            Self::Refuse(_) => op::REFUSE,
            Self::Clients => op::CLIENTS,
            Self::Admins => op::ADMINS,
            Self::Block(_) => op::BLOCK,
            Self::Unblock(_) => op::UNBLOCK,
            Self::Allow(_) => op::ALLOW,
            Self::AddAdmin(_) => op::ADD_ADMIN,
            Self::RemoveAdmin(_) => op::REMOVE_ADMIN,
            Self::Login(_) => op::LOGIN,
            Self::Set(s, on) => op::SET | (*s as u8) << 1 | u8::from(*on),
        }
    }

    /// True if it, or its reply, may put a name or phone number on air (the
    /// app warns before sending it in plain). Accept and refuse replies name
    /// who they acted on.
    pub fn reveals_personal_data(&self) -> bool {
        match self {
            Self::Requests
            | Self::Clients
            | Self::Admins
            | Self::Allow(_)
            | Self::Accept(_)
            | Self::Refuse(_) => true,
            Self::Block(t) | Self::Unblock(t) | Self::AddAdmin(t) | Self::RemoveAdmin(t) => {
                matches!(t, Target::Contact(_))
            }
            _ => false,
        }
    }

    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        let mut b = vec![self.opcode()];
        match self {
            Self::Accept(n) | Self::Refuse(n) => b.push(*n),
            Self::Block(t) | Self::Unblock(t) | Self::AddAdmin(t) | Self::RemoveAdmin(t) => {
                t.encode(&mut b)?
            }
            Self::Allow(a) => {
                if matches!(a, Address::MeshcoreContact(_)) {
                    return Err(Error::Range("MeshCore contacts join by request only"));
                }
                a.encode(&mut b)?;
            }
            Self::Login(pw) => b.extend(textcodec::encode(pw)),
            _ => {}
        }
        Ok(b)
    }

    pub fn decode(b: &[u8]) -> Result<Self, Error> {
        let (&code, rest) = b.split_first().ok_or(Error::TooShort)?;
        let none = |c: Self| {
            if rest.is_empty() {
                Ok(c)
            } else {
                Err(Error::Malformed("admin command takes no arguments"))
            }
        };
        let num = || match rest {
            [n] => Ok(*n),
            _ => Err(Error::Malformed("request number is 1 byte")),
        };
        if code & 0xC0 == op::SET {
            if code & 0x30 != 0 {
                return Err(Error::Malformed("unknown admin opcode"));
            }
            return none(Self::Set(
                Switch::from_u8((code >> 1) & 0x07)?,
                code & 1 == 1,
            ));
        }
        match code {
            op::STATUS => none(Self::Status),
            op::REQUESTS => none(Self::Requests),
            op::ACCEPT => Ok(Self::Accept(num()?)),
            op::REFUSE => Ok(Self::Refuse(num()?)),
            op::CLIENTS => none(Self::Clients),
            op::ADMINS => none(Self::Admins),
            op::BLOCK => Ok(Self::Block(Target::decode(rest)?)),
            op::UNBLOCK => Ok(Self::Unblock(Target::decode(rest)?)),
            op::ALLOW => {
                let (a, n) = Address::decode(rest)?;
                if n != rest.len() || matches!(a, Address::MeshcoreContact(_)) {
                    return Err(Error::Malformed(
                        "allow takes one Signal or WhatsApp address",
                    ));
                }
                Ok(Self::Allow(a))
            }
            op::ADD_ADMIN => Ok(Self::AddAdmin(Target::decode(rest)?)),
            op::REMOVE_ADMIN => Ok(Self::RemoveAdmin(Target::decode(rest)?)),
            op::LOGIN => Ok(Self::Login(textcodec::decode(rest)?)),
            _ => Err(Error::Malformed("unknown admin opcode")),
        }
    }
}

/// How a command went. Append only.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Outcome {
    Ok = 0,
    NotAdmin = 1,
    /// No such alias, contact or pending request.
    Unknown = 2,
    WrongPassword = 3,
    /// The password came in signed plaintext: refused even if right.
    PasswordInClear = 4,
    /// Removing the last admin while password login is off.
    LastAdmin = 5,
    /// Password login is switched off.
    LoginOff = 6,
    /// Too many wrong passwords: try again in an hour.
    Locked = 7,
    /// Not possible (e.g. a pending list that is full).
    Refused = 8,
}

impl Outcome {
    const ALL: [Outcome; 9] = [
        Self::Ok,
        Self::NotAdmin,
        Self::Unknown,
        Self::WrongPassword,
        Self::PasswordInClear,
        Self::LastAdmin,
        Self::LoginOff,
        Self::Locked,
        Self::Refused,
    ];
}

/// Network of a user or contact in replies: 0 is a field user (radio).
pub mod who {
    pub const RADIO: u8 = 0;
    pub const SIGNAL: u8 = 1;
    pub const WHATSAPP: u8 = 2;
    pub const MESHCORE: u8 = 3;

    pub fn of(a: &crate::bridge::Address) -> u8 {
        use crate::bridge::Address::*;
        match a {
            SignalPhone(_) | SignalUsername(_) => SIGNAL,
            WhatsappPhone(_) => WHATSAPP,
            MeshcoreContact(_) => MESHCORE,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RequestEntry {
    pub num: u8,
    /// [`who`].
    pub who: u8,
    /// [`user_id`] or [`meshcore_id`]; on air only for radio and MeshCore.
    pub id: Option<Id>,
    /// Alias, profile name or number, or MeshCore advert name.
    pub name: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClientEntry {
    /// 0 registered, 1 pending, 2 refused, 3 unregistered, 4 blocked.
    pub status: u8,
    /// Minutes since last heard; `None` never (0xFFFF on air, capped below).
    pub heard_min: Option<u16>,
    /// [`user_id`].
    pub id: Id,
    pub alias: String,
}

/// How someone became admin. Append only.
pub mod how {
    pub const SSH: u8 = 0;
    pub const BY_ADMIN: u8 = 1;
    pub const PASSWORD: u8 = 2;
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdminEntry {
    /// [`who`].
    pub who: u8,
    /// [`how`].
    pub how: u8,
    /// As in [`RequestEntry::id`].
    pub id: Option<Id>,
    pub name: String,
}

/// Bits of [`Reply::NewRequests`]: networks with new requests (bit = [`who`]).
pub fn network_bit(who: u8) -> u8 {
    1 << who
}

/// A Pharos's answer or notice to a field user.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Reply {
    /// Result of a command; `subject` names who it acted on (e.g. the
    /// request that was accepted).
    Done {
        opcode: u8,
        outcome: Outcome,
        subject: Option<String>,
    },
    Status {
        switches: Switches,
        registered: u8,
        requests: u8,
        listening: u8,
        queued: u16,
        contacts: u16,
    },
    Requests(Vec<RequestEntry>),
    Clients(Vec<ClientEntry>),
    Admins(Vec<AdminEntry>),
    /// To admins: new access requests, no personal data. `networks` is a
    /// mask of [`network_bit`]s, `pending` the total now pending.
    NewRequests {
        networks: u8,
        pending: u8,
    },
    /// To admins: another admin changed the switches.
    SwitchesChanged(Switches),
    /// To admins: someone became admin, or stopped being one.
    AdminChanged {
        added: bool,
        who: u8,
        /// As in [`RequestEntry::id`].
        id: Option<Id>,
        name: String,
    },
}

mod kind {
    pub const DONE: u8 = 0;
    pub const STATUS: u8 = 1;
    pub const REQUESTS: u8 = 2;
    pub const CLIENTS: u8 = 3;
    pub const ADMINS: u8 = 4;
    pub const NEW_REQUESTS: u8 = 5;
    pub const SWITCHES_CHANGED: u8 = 6;
    pub const ADMIN_CHANGED: u8 = 7;
}

/// Length-prefixed text inside lists.
fn put_text(out: &mut Vec<u8>, s: &str) -> Result<(), Error> {
    let t = textcodec::encode(s);
    out.push(u8::try_from(t.len()).map_err(|_| Error::Range("name too long"))?);
    out.extend(t);
    Ok(())
}

fn take_text(b: &[u8]) -> Result<(String, &[u8]), Error> {
    let (&n, rest) = b.split_first().ok_or(Error::TooShort)?;
    let t = rest.get(..usize::from(n)).ok_or(Error::TooShort)?;
    Ok((textcodec::decode(t)?, &rest[usize::from(n)..]))
}

/// Radio users and MeshCore contacts carry an [`Id`] on air.
fn has_id(who: u8) -> bool {
    who == who::RADIO || who == who::MESHCORE
}

fn put_id(out: &mut Vec<u8>, who: u8, id: &Option<Id>) -> Result<(), Error> {
    match (has_id(who), id) {
        (true, Some(id)) => out.extend_from_slice(id),
        (false, None) => {}
        _ => return Err(Error::Range("id for radio and MeshCore entries only")),
    }
    Ok(())
}

fn take_id(b: &[u8], who: u8) -> Result<(Option<Id>, &[u8]), Error> {
    if !has_id(who) {
        return Ok((None, b));
    }
    let (id, r) = take::<8>(b)?;
    Ok((Some(id), r))
}

fn take<const N: usize>(b: &[u8]) -> Result<([u8; N], &[u8]), Error> {
    let h = b.get(..N).ok_or(Error::TooShort)?;
    Ok((h.try_into().unwrap(), &b[N..]))
}

impl Reply {
    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        let mut b = Vec::new();
        match self {
            Self::Done {
                opcode,
                outcome,
                subject,
            } => {
                b.extend([kind::DONE, *opcode, *outcome as u8]);
                if let Some(s) = subject.as_deref().filter(|s| !s.is_empty()) {
                    b.extend(textcodec::encode(s));
                }
            }
            Self::Status {
                switches,
                registered,
                requests,
                listening,
                queued,
                contacts,
            } => {
                b.extend([kind::STATUS, switches.0, *registered, *requests, *listening]);
                b.extend(queued.to_be_bytes());
                b.extend(contacts.to_be_bytes());
            }
            Self::Requests(l) => {
                b.push(kind::REQUESTS);
                for e in l {
                    b.extend([e.num, e.who]);
                    put_id(&mut b, e.who, &e.id)?;
                    put_text(&mut b, &e.name)?;
                }
            }
            Self::Clients(l) => {
                b.push(kind::CLIENTS);
                for e in l {
                    b.push(e.status);
                    b.extend(
                        e.heard_min
                            .map_or(u16::MAX, |m| m.min(u16::MAX - 1))
                            .to_be_bytes(),
                    );
                    b.extend_from_slice(&e.id);
                    put_text(&mut b, &e.alias)?;
                }
            }
            Self::Admins(l) => {
                b.push(kind::ADMINS);
                for e in l {
                    b.extend([e.who, e.how]);
                    put_id(&mut b, e.who, &e.id)?;
                    put_text(&mut b, &e.name)?;
                }
            }
            Self::NewRequests { networks, pending } => {
                b.extend([kind::NEW_REQUESTS, *networks, *pending]);
            }
            Self::SwitchesChanged(s) => b.extend([kind::SWITCHES_CHANGED, s.0]),
            Self::AdminChanged {
                added,
                who,
                id,
                name,
            } => {
                b.extend([kind::ADMIN_CHANGED, u8::from(*added), *who]);
                put_id(&mut b, *who, id)?;
                b.extend(textcodec::encode(name));
            }
        }
        Ok(b)
    }

    pub fn decode(b: &[u8]) -> Result<Self, Error> {
        let (&k, mut rest) = b.split_first().ok_or(Error::TooShort)?;
        Ok(match k {
            kind::DONE => {
                let ([opcode, outcome], r) = take::<2>(rest)?;
                let outcome = *Outcome::ALL
                    .get(usize::from(outcome))
                    .ok_or(Error::Malformed("unknown outcome"))?;
                let subject = if r.is_empty() {
                    None
                } else {
                    Some(textcodec::decode(r)?)
                };
                Self::Done {
                    opcode,
                    outcome,
                    subject,
                }
            }
            kind::STATUS => {
                let ([s, registered, requests, listening, q0, q1, c0, c1], r) = take::<8>(rest)?;
                if !r.is_empty() {
                    return Err(Error::Malformed("status length"));
                }
                Self::Status {
                    switches: Switches(s),
                    registered,
                    requests,
                    listening,
                    queued: u16::from_be_bytes([q0, q1]),
                    contacts: u16::from_be_bytes([c0, c1]),
                }
            }
            kind::REQUESTS => {
                let mut l = Vec::new();
                while !rest.is_empty() {
                    let ([num, who], r) = take::<2>(rest)?;
                    let (id, r) = take_id(r, who)?;
                    let (name, r) = take_text(r)?;
                    l.push(RequestEntry { num, who, id, name });
                    rest = r;
                }
                Self::Requests(l)
            }
            kind::CLIENTS => {
                let mut l = Vec::new();
                while !rest.is_empty() {
                    let ([status, h0, h1], r) = take::<3>(rest)?;
                    let (id, r) = take::<8>(r)?;
                    let (alias, r) = take_text(r)?;
                    let h = u16::from_be_bytes([h0, h1]);
                    l.push(ClientEntry {
                        status,
                        heard_min: (h != u16::MAX).then_some(h),
                        id,
                        alias,
                    });
                    rest = r;
                }
                Self::Clients(l)
            }
            kind::ADMINS => {
                let mut l = Vec::new();
                while !rest.is_empty() {
                    let ([who, how], r) = take::<2>(rest)?;
                    let (id, r) = take_id(r, who)?;
                    let (name, r) = take_text(r)?;
                    l.push(AdminEntry { who, how, id, name });
                    rest = r;
                }
                Self::Admins(l)
            }
            kind::NEW_REQUESTS => match rest {
                [networks, pending] => Self::NewRequests {
                    networks: *networks,
                    pending: *pending,
                },
                _ => return Err(Error::Malformed("new requests length")),
            },
            kind::SWITCHES_CHANGED => match rest {
                [s] => Self::SwitchesChanged(Switches(*s)),
                _ => return Err(Error::Malformed("switches length")),
            },
            kind::ADMIN_CHANGED => {
                let ([added, who], r) = take::<2>(rest)?;
                let (id, r) = take_id(r, who)?;
                Self::AdminChanged {
                    added: added != 0,
                    who,
                    id,
                    name: textcodec::decode(r)?,
                }
            }
            _ => return Err(Error::Malformed("unknown admin reply")),
        })
    }
}

/// `network` codes cover Signal phones and usernames separately; replies use [`who`].
pub fn who_of_network(net: u8) -> u8 {
    match net {
        network::SIGNAL_PHONE | network::SIGNAL_USERNAME => who::SIGNAL,
        network::WHATSAPP_PHONE => who::WHATSAPP,
        _ => who::MESHCORE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn commands_roundtrip_and_switches_fit_in_one_byte() {
        let cmds = [
            Command::Status,
            Command::Requests,
            Command::Accept(3),
            Command::Refuse(255),
            Command::Clients,
            Command::Admins,
            Command::Block(Target::User([7; 8])),
            Command::Unblock(Target::Contact(Address::WhatsappPhone(
                "34600123456".into(),
            ))),
            Command::Allow(Address::SignalUsername("mama.01".into())),
            Command::AddAdmin(Target::Meshcore([1, 2, 3, 4, 5, 6, 7, 8])),
            Command::RemoveAdmin(Target::User([9; 8])),
            Command::Set(Switch::Emergency, true),
            Command::Set(Switch::Open, false),
            Command::Login("faro viejo".into()),
        ];
        for c in cmds {
            let b = c.encode().unwrap();
            assert_eq!(Command::decode(&b).unwrap(), c, "{b:02X?}");
        }
        assert_eq!(
            Command::Set(Switch::Stealth, true).encode().unwrap(),
            [0x49]
        );
        assert_eq!(Command::Status.encode().unwrap().len(), 1);
        assert_eq!(Command::Accept(7).encode().unwrap().len(), 2);
        assert_eq!(
            Command::Block(Target::Meshcore([0; 8]))
                .encode()
                .unwrap()
                .len(),
            10,
            "a known MeshCore contact by its 8-byte id, not its 32-byte key"
        );
        assert_eq!(
            Command::Block(Target::Contact(Address::MeshcoreContact([3; 32])))
                .encode()
                .unwrap(),
            Command::Block(Target::Meshcore([3; 8])).encode().unwrap(),
            "a full MeshCore key goes out as its id"
        );
        assert!(
            Command::Allow(Address::MeshcoreContact([0; 32]))
                .encode()
                .is_err()
        );
        assert!(Command::decode(&[0x7F]).is_err());
        assert!(Command::decode(&[op::STATUS, 0]).is_err());
    }

    #[test]
    fn replies_roundtrip() {
        let mut sw = Switches::default();
        sw.set(Switch::Requests, true);
        sw.set(Switch::Login, true);
        assert!(sw.get(Switch::Login) && !sw.get(Switch::Open));
        let replies = [
            Reply::Done {
                opcode: op::ACCEPT,
                outcome: Outcome::Ok,
                subject: Some("Mamá +34611111111".into()),
            },
            Reply::Done {
                opcode: op::LOGIN,
                outcome: Outcome::PasswordInClear,
                subject: None,
            },
            Reply::Status {
                switches: sw,
                registered: 3,
                requests: 1,
                listening: 1,
                queued: 2,
                contacts: 4,
            },
            Reply::Requests(vec![
                RequestEntry {
                    num: 4,
                    who: who::WHATSAPP,
                    id: None,
                    name: "Papá".into(),
                },
                RequestEntry {
                    num: 5,
                    who: who::MESHCORE,
                    id: Some([0xA1; 8]),
                    name: "Refugio Góriz".into(),
                },
                RequestEntry {
                    num: 6,
                    who: who::RADIO,
                    id: Some([0x4F; 8]),
                    name: "bea".into(),
                },
            ]),
            Reply::Clients(vec![ClientEntry {
                status: 0,
                heard_min: None,
                id: [0x4F; 8],
                alias: "bea".into(),
            }]),
            Reply::Admins(vec![AdminEntry {
                who: who::RADIO,
                how: how::SSH,
                id: Some([1; 8]),
                name: "alvaro".into(),
            }]),
            Reply::NewRequests {
                networks: network_bit(who::WHATSAPP) | network_bit(who::RADIO),
                pending: 2,
            },
            Reply::SwitchesChanged(sw),
            Reply::AdminChanged {
                added: true,
                who: who::SIGNAL,
                id: None,
                name: "+34600000000".into(),
            },
        ];
        for r in replies {
            let b = r.encode().unwrap();
            assert_eq!(Reply::decode(&b).unwrap(), r, "{b:02X?}");
        }
        assert_eq!(
            Reply::NewRequests {
                networks: 1,
                pending: 1
            }
            .encode()
            .unwrap()
            .len(),
            3,
            "the admin notice carries no personal data"
        );
    }

    fn data_encoding_free_hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn ids_must_match_their_network() {
        let bad = Reply::Requests(vec![RequestEntry {
            num: 1,
            who: who::RADIO,
            id: None,
            name: "x".into(),
        }]);
        assert!(bad.encode().is_err());
    }

    #[test]
    fn personal_data_is_flagged() {
        assert!(Command::Requests.reveals_personal_data());
        assert!(
            Command::Allow(Address::WhatsappPhone("34600123456".into())).reveals_personal_data()
        );
        assert!(!Command::Block(Target::User([1; 8])).reveals_personal_data());
        let k = PublicKey([5; 32]);
        assert_eq!(
            user_id(&k)[..2],
            data_encoding_free_hex(&k.safety_number()[..4]),
            "the id is the start of the safety code"
        );
        assert!(
            Command::Accept(3).reveals_personal_data(),
            "the reply names who"
        );
        assert!(!Command::Status.reveals_personal_data());
        assert!(!Command::Set(Switch::Open, true).reveals_personal_data());
    }
}
