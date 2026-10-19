//! A station's identity, its contacts, Pharos stations and groups, with the
//! counters and replay windows of every link (see `docs/spec/crypto.md`).
//!
//! Serialize the [`Keyring`] to persist it, and persist after every
//! [`Keyring::seal`] **before** transmitting: a counter must never repeat.
//! The serialized form holds the identity secret and group keys.

use crate::Error;
use crate::ack::{ACK_LEN, Ack};
use crate::crypto::{
    COMPACT_TAG_LEN, GroupId, GroupKey, Identity, PublicKey, Root, TAG_LEN, apply_cipher,
    group_root, hmac, pair_root, subtle_eq,
};
use crate::header::{BROADCAST, HEADER_LEN, Header, MsgType, VERSION, peek_type};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Counters jump this far after restoring a backup, so message keys used
/// after the backup was taken are not used again.
pub const RESTORE_COUNTER_JUMP: u32 = 4096;
/// Replay window: how far behind the newest counter a frame may arrive.
pub const REPLAY_WINDOW: u16 = 64;
/// Compact poll frame length (one JS8 frame).
pub const POLL_LEN: usize = 9;
const COUNTER_LIMIT: u32 = 1 << 16;

/// What a frame is sealed for.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum LinkRef {
    Peer(PublicKey),
    Group(GroupId),
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PeerKind {
    #[default]
    Person,
    Pharos,
}

/// Public facts about a station: what a `skypost:id/` URI carries.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Card {
    pub key: PublicKey,
    pub alias: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub callsign: Option<String>,
    #[serde(default)]
    pub kind: PeerKind,
}

/// Highest counter seen plus a bitmap of the [`REPLAY_WINDOW`] before it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReplayWindow {
    top: Option<u16>,
    seen: u64,
}

#[derive(Debug, PartialEq, Eq)]
enum Seen {
    Fresh,
    Again,
    TooOld,
}

impl ReplayWindow {
    fn check(&self, c: u16) -> Seen {
        let Some(top) = self.top else {
            return Seen::Fresh;
        };
        if c > top {
            return Seen::Fresh;
        }
        let d = top - c;
        if d >= REPLAY_WINDOW {
            Seen::TooOld
        } else if self.seen >> d & 1 == 1 {
            Seen::Again
        } else {
            Seen::Fresh
        }
    }

    fn commit(&mut self, c: u16) {
        match self.top {
            Some(top) if c <= top => self.seen |= 1 << (top - c),
            Some(top) => {
                let shift = u32::from(c - top);
                self.seen = self.seen.checked_shl(shift).unwrap_or(0) | 1;
                self.top = Some(c);
            }
            None => {
                self.top = Some(c);
                self.seen = 1;
            }
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct LinkState {
    send_next: u32,
    windows: BTreeMap<PublicKey, ReplayWindow>,
}

impl LinkState {
    fn take_counter(&mut self) -> Result<u16, Error> {
        let c = u16::try_from(self.send_next).map_err(|_| Error::CounterExhausted)?;
        self.send_next += 1;
        Ok(c)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Peer {
    pub card: Card,
    /// Fingerprint confirmed in person (QR) or by comparing safety numbers.
    #[serde(default)]
    pub verified: bool,
    /// Frames from a blocked peer are dropped unopened: no delivery, no ACK,
    /// no answer to its pings; its group messages are dropped too.
    #[serde(default)]
    pub blocked: bool,
    #[serde(default)]
    state: LinkState,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Group {
    pub key: GroupKey,
    pub name: String,
    /// Known members (public key → alias), this station included.
    pub roster: BTreeMap<PublicKey, String>,
    /// Frames for a blocked group are dropped unopened.
    #[serde(default)]
    pub blocked: bool,
    #[serde(default)]
    state: LinkState,
}

impl Group {
    pub fn id(&self) -> GroupId {
        self.key.id()
    }
}

/// An authenticated frame, opened.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Opened {
    pub header: Header,
    pub link: LinkRef,
    pub sender: PublicKey,
    /// Plaintext body (for a compact Poll: the listen-minutes byte).
    pub body: Vec<u8>,
    /// A `Register` from a key that is not a peer yet. Nothing was recorded:
    /// call [`Keyring::accept_register`] to add it, or ignore the frame.
    pub new_peer: bool,
}

/// A sealed frame and the counter it used.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sealed {
    pub wire: Vec<u8>,
    pub counter: u16,
}

/// A verified ACK.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AckInfo {
    pub link: LinkRef,
    pub acker: PublicKey,
    pub counter: u16,
    pub snr_db: i8,
    pub dt_ds: i8,
    pub more: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Keyring {
    identity: Identity,
    me: Card,
    #[serde(default)]
    peers: BTreeMap<PublicKey, Peer>,
    #[serde(default)]
    groups: BTreeMap<GroupId, Group>,
}

impl Keyring {
    pub fn new(identity: Identity, alias: &str, callsign: Option<&str>, kind: PeerKind) -> Self {
        let me = Card {
            key: identity.public(),
            alias: alias.to_owned(),
            callsign: callsign.map(str::to_owned),
            kind,
        };
        Self {
            identity,
            me,
            peers: BTreeMap::new(),
            groups: BTreeMap::new(),
        }
    }

    pub fn card(&self) -> &Card {
        &self.me
    }

    pub fn set_alias(&mut self, alias: &str, callsign: Option<&str>) {
        self.me.alias = alias.to_owned();
        self.me.callsign = callsign.map(str::to_owned);
    }

    pub fn public(&self) -> PublicKey {
        self.me.key
    }

    pub fn short_id(&self) -> u8 {
        self.me.key.short_id()
    }

    /// Add or update a contact or Pharos. Counters survive an update.
    pub fn add_peer(&mut self, card: Card, verified: bool) -> Result<(), Error> {
        if card.key == self.me.key {
            return Err(Error::Range("that is this station's own key"));
        }
        pair_root(&self.identity, &card.key)?;
        match self.peers.get_mut(&card.key) {
            Some(p) => {
                p.card = card;
                p.verified |= verified;
            }
            None => {
                self.peers.insert(
                    card.key,
                    Peer {
                        card,
                        verified,
                        blocked: false,
                        state: LinkState::default(),
                    },
                );
            }
        }
        Ok(())
    }

    pub fn remove_peer(&mut self, key: &PublicKey) -> Option<Peer> {
        self.peers.remove(key)
    }

    pub fn peer(&self, key: &PublicKey) -> Option<&Peer> {
        self.peers.get(key)
    }

    /// Block or unblock a contact, Pharos or group (see [`Peer::blocked`]).
    pub fn set_blocked(&mut self, link: &LinkRef, blocked: bool) -> Result<(), Error> {
        match link {
            LinkRef::Peer(k) => self.peers.get_mut(k).ok_or(Error::UnknownLink)?.blocked = blocked,
            LinkRef::Group(id) => {
                self.groups.get_mut(id).ok_or(Error::UnknownLink)?.blocked = blocked
            }
        }
        Ok(())
    }

    fn peer_blocked(&self, k: &PublicKey) -> bool {
        self.peers.get(k).is_some_and(|p| p.blocked)
    }

    pub fn set_verified(&mut self, key: &PublicKey) -> Result<(), Error> {
        self.peers.get_mut(key).ok_or(Error::UnknownLink)?.verified = true;
        Ok(())
    }

    pub fn peers(&self) -> impl Iterator<Item = &Peer> {
        self.peers.values()
    }

    /// Join (or create) a group. This station is put on the roster.
    pub fn add_group(&mut self, key: GroupKey, name: &str) -> GroupId {
        let id = key.id();
        let me = (self.me.key, self.me.alias.clone());
        self.groups
            .entry(id)
            .and_modify(|g| g.name = name.to_owned())
            .or_insert_with(|| Group {
                key,
                name: name.to_owned(),
                roster: BTreeMap::from([me]),
                blocked: false,
                state: LinkState::default(),
            });
        id
    }

    pub fn add_group_member(
        &mut self,
        id: &GroupId,
        key: PublicKey,
        alias: &str,
    ) -> Result<(), Error> {
        let g = self.groups.get_mut(id).ok_or(Error::UnknownLink)?;
        g.roster.insert(key, alias.to_owned());
        Ok(())
    }

    pub fn remove_group(&mut self, id: &GroupId) -> Option<Group> {
        self.groups.remove(id)
    }

    pub fn group(&self, id: &GroupId) -> Option<&Group> {
        self.groups.get(id)
    }

    pub fn groups(&self) -> impl Iterator<Item = &Group> {
        self.groups.values()
    }

    /// Call once after restoring a backup: see [`RESTORE_COUNTER_JUMP`].
    pub fn after_restore(&mut self) {
        let states = self
            .peers
            .values_mut()
            .map(|p| &mut p.state)
            .chain(self.groups.values_mut().map(|g| &mut g.state));
        for s in states {
            s.send_next = (s.send_next + RESTORE_COUNTER_JUMP).min(COUNTER_LIMIT);
        }
    }

    fn root(&self, link: &LinkRef) -> Result<Root, Error> {
        match link {
            LinkRef::Peer(k) => pair_root(&self.identity, k),
            LinkRef::Group(id) => Ok(group_root(
                &self.groups.get(id).ok_or(Error::UnknownLink)?.key,
            )),
        }
    }

    fn state_mut(&mut self, link: &LinkRef) -> Result<&mut LinkState, Error> {
        match link {
            LinkRef::Peer(k) => self.peers.get_mut(k).map(|p| &mut p.state),
            LinkRef::Group(id) => self.groups.get_mut(id).map(|g| &mut g.state),
        }
        .ok_or(Error::UnknownLink)
    }

    fn window(&self, link: &LinkRef, sender: &PublicKey) -> ReplayWindow {
        let state = match link {
            LinkRef::Peer(k) => self.peers.get(k).map(|p| &p.state),
            LinkRef::Group(id) => self.groups.get(id).map(|g| &g.state),
        };
        state
            .and_then(|s| s.windows.get(sender))
            .copied()
            .unwrap_or_default()
    }

    /// Short id a frame on `link` is addressed to.
    pub fn dst_of(&self, link: &LinkRef) -> u8 {
        match link {
            LinkRef::Peer(k) => k.short_id(),
            LinkRef::Group(id) => id.short_id(),
        }
    }

    /// Counter the next frame on `link` will use.
    pub fn next_counter(&self, link: &LinkRef) -> Result<u16, Error> {
        let s = match link {
            LinkRef::Peer(k) => self.peers.get(k).map(|p| &p.state),
            LinkRef::Group(id) => self.groups.get(id).map(|g| &g.state),
        }
        .ok_or(Error::UnknownLink)?;
        u16::try_from(s.send_next).map_err(|_| Error::CounterExhausted)
    }

    /// Seal header + body + 8-byte tag for `link`. Persist before transmitting.
    pub fn seal(
        &mut self,
        link: &LinkRef,
        msg_type: MsgType,
        encrypted: bool,
        body: &[u8],
    ) -> Result<Sealed, Error> {
        if matches!(msg_type, MsgType::Poll | MsgType::Ack) || msg_type.is_unauthenticated() {
            return Err(Error::Range(
                "compact and unauthenticated types have their own encoders",
            ));
        }
        let root = self.root(link)?;
        let header = Header {
            encrypted,
            msg_type,
            src: self.short_id(),
            dst: self.dst_of(link),
            counter: self.next_counter(link)?,
        };
        let hb = header.encode()?;
        let keys = root.message_keys(&self.me.key, header.counter);
        let mut b = body.to_vec();
        if encrypted {
            apply_cipher(&keys.enc, &hb, &mut b);
        }
        let tag = hmac(&keys.mac, &[&hb, &b]);
        let mut wire = Vec::with_capacity(HEADER_LEN + b.len() + TAG_LEN);
        wire.extend_from_slice(&hb);
        wire.extend_from_slice(&b);
        wire.extend_from_slice(&tag[..TAG_LEN]);
        let counter = self.state_mut(link)?.take_counter()?;
        debug_assert_eq!(counter, header.counter);
        Ok(Sealed { wire, counter })
    }

    /// Compact 9-byte poll / ping to a peer (normally a Pharos).
    pub fn seal_poll(&mut self, to: &PublicKey, listen_min: u8) -> Result<Sealed, Error> {
        let link = LinkRef::Peer(*to);
        let root = self.root(&link)?;
        let header = Header {
            encrypted: false,
            msg_type: MsgType::Poll,
            src: self.short_id(),
            dst: to.short_id(),
            counter: self.next_counter(&link)?,
        };
        let mut wire = header.encode()?.to_vec();
        wire.push(listen_min);
        let keys = root.message_keys(&self.me.key, header.counter);
        let tag = hmac(&keys.mac, &[&wire]);
        wire.extend_from_slice(&tag[..COMPACT_TAG_LEN]);
        let counter = self.state_mut(&link)?.take_counter()?;
        Ok(Sealed { wire, counter })
    }

    fn candidates(&self, header: &Header, body: &[u8]) -> Vec<(LinkRef, PublicKey)> {
        let mut c = self.all_candidates(header, body);
        c.retain(|(link, sender)| {
            !self.peer_blocked(sender)
                && !matches!(link, LinkRef::Group(id) if self.groups[id].blocked)
        });
        c
    }

    fn all_candidates(&self, header: &Header, body: &[u8]) -> Vec<(LinkRef, PublicKey)> {
        let mine = header.dst == self.short_id() || header.dst == BROADCAST;
        match header.msg_type {
            MsgType::Register => match body.get(..32) {
                Some(k) if mine => {
                    let k = PublicKey(k.try_into().unwrap());
                    vec![(LinkRef::Peer(k), k)]
                }
                _ => vec![],
            },
            MsgType::GroupJoin => match body.get(..32) {
                Some(k) => {
                    let k = PublicKey(k.try_into().unwrap());
                    self.groups
                        .keys()
                        .filter(|id| id.short_id() == header.dst)
                        .map(|id| (LinkRef::Group(*id), k))
                        .collect()
                }
                None => vec![],
            },
            _ => {
                let mut c: Vec<_> = if mine {
                    self.peers
                        .keys()
                        .filter(|k| k.short_id() == header.src)
                        .map(|k| (LinkRef::Peer(*k), *k))
                        .collect()
                } else {
                    vec![]
                };
                for (id, g) in &self.groups {
                    if id.short_id() != header.dst {
                        continue;
                    }
                    for k in g.roster.keys() {
                        if *k != self.me.key && k.short_id() == header.src {
                            c.push((LinkRef::Group(*id), *k));
                        }
                    }
                }
                c
            }
        }
    }

    /// Verify and open an authenticated frame (anything but ACKs and the
    /// unauthenticated types). State changes only if a tag verifies.
    pub fn open(&mut self, frame: &[u8]) -> Result<Opened, Error> {
        let t = peek_type(frame)?;
        if t == MsgType::Ack || t.is_unauthenticated() {
            return Err(Error::Malformed("not an authenticated message frame"));
        }
        let header = Header::decode(frame)?;
        let compact = t == MsgType::Poll;
        let (signed, rx_tag, tag_len) = if compact {
            if frame.len() != POLL_LEN {
                return Err(Error::Malformed("poll must be 9 bytes"));
            }
            (
                &frame[..POLL_LEN - COMPACT_TAG_LEN],
                &frame[POLL_LEN - COMPACT_TAG_LEN..],
                COMPACT_TAG_LEN,
            )
        } else {
            if frame.len() < HEADER_LEN + TAG_LEN {
                return Err(Error::TooShort);
            }
            let split = frame.len() - TAG_LEN;
            (&frame[..split], &frame[split..], TAG_LEN)
        };
        let body_rx = &signed[HEADER_LEN..];
        let candidates = self.candidates(&header, body_rx);
        if candidates.is_empty() {
            return Err(Error::UnknownSender);
        }
        let mut err = Error::AuthFailed;
        for (link, sender) in candidates {
            let Ok(root) = (match &link {
                LinkRef::Peer(k) => pair_root(&self.identity, k),
                LinkRef::Group(_) => self.root(&link),
            }) else {
                continue;
            };
            let seen = self.window(&link, &sender).check(header.counter);
            if seen == Seen::TooOld {
                err = Error::Replay {
                    counter: header.counter,
                };
                continue;
            }
            let keys = root.message_keys(&sender, header.counter);
            let expected = hmac(&keys.mac, &[signed]);
            if !subtle_eq(&expected[..tag_len], rx_tag) {
                continue;
            }
            if matches!(header.msg_type, MsgType::Register | MsgType::GroupJoin)
                && (header.encrypted || sender.short_id() != header.src)
            {
                return Err(Error::Malformed(
                    "register/join must be signed and match src",
                ));
            }
            if seen == Seen::Again {
                return Err(Error::Duplicate {
                    link,
                    sender,
                    counter: header.counter,
                });
            }
            let mut body = body_rx.to_vec();
            if header.encrypted && !compact {
                let hb: [u8; HEADER_LEN] = signed[..HEADER_LEN].try_into().unwrap();
                apply_cipher(&keys.enc, &hb, &mut body);
            }
            let new_peer = matches!(link, LinkRef::Peer(k) if !self.peers.contains_key(&k));
            if !new_peer {
                if let (LinkRef::Group(id), MsgType::GroupJoin) = (&link, header.msg_type) {
                    let alias = crate::textcodec::decode(&body[32..]).unwrap_or_default();
                    self.add_group_member(id, sender, &alias)?;
                }
                self.commit(&link, &sender, header.counter)?;
            }
            return Ok(Opened {
                header,
                link,
                sender,
                body,
                new_peer,
            });
        }
        Err(err)
    }

    fn commit(&mut self, link: &LinkRef, sender: &PublicKey, counter: u16) -> Result<(), Error> {
        self.state_mut(link)?
            .windows
            .entry(*sender)
            .or_default()
            .commit(counter);
        Ok(())
    }

    /// Add the sender of a verified `Register` (an [`Opened`] or
    /// [`crate::Decoded`] with `new_peer`) and record the frame's counter.
    pub fn accept_register(&mut self, card: Card, counter: u16) -> Result<(), Error> {
        let key = card.key;
        self.add_peer(card, false)?;
        self.commit(&LinkRef::Peer(key), &key, counter)
    }

    /// Build the ACK for a frame received on `link` from `to`.
    pub fn ack(
        &self,
        link: &LinkRef,
        to: &PublicKey,
        counter: u16,
        snr_db: i8,
        dt_ds: i8,
        more: bool,
    ) -> Result<[u8; ACK_LEN], Error> {
        let a = Ack {
            more,
            src: self.short_id(),
            dst: to.short_id(),
            acked_counter_lo: counter as u8,
            snr_db,
            dt_ds,
        };
        let key = self.root(link)?.ack_key(&self.me.key);
        a.encode(&key, counter)
    }

    /// Verify an ACK against the frames this station is waiting on.
    pub fn open_ack(&self, frame: &[u8], waiting: &[(LinkRef, u16)]) -> Result<AckInfo, Error> {
        let a = Ack::peek(frame)?;
        if a.dst != self.short_id() {
            return Err(Error::UnknownSender);
        }
        for (link, counter) in waiting {
            if *counter as u8 != a.acked_counter_lo {
                continue;
            }
            let ackers: Vec<PublicKey> = match link {
                LinkRef::Peer(k) => vec![*k],
                LinkRef::Group(id) => self
                    .groups
                    .get(id)
                    .map(|g| {
                        g.roster
                            .keys()
                            .copied()
                            .filter(|k| *k != self.me.key)
                            .collect()
                    })
                    .unwrap_or_default(),
            };
            let Ok(root) = self.root(link) else { continue };
            for acker in ackers
                .into_iter()
                .filter(|k| k.short_id() == a.src && !self.peer_blocked(k))
            {
                if Ack::verify(frame, &root.ack_key(&acker), *counter).is_ok() {
                    return Ok(AckInfo {
                        link: *link,
                        acker,
                        counter: *counter,
                        snr_db: a.snr_db,
                        dt_ds: a.dt_ds,
                        more: a.more,
                    });
                }
            }
        }
        Err(Error::AuthFailed)
    }
}

/// Byte 0 of a frame of `t` with the given flag bit.
pub(crate) fn byte0(t: MsgType, flag: bool) -> u8 {
    (VERSION << 6) | (u8::from(flag) << 5) | t as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(crate) fn station(seed: u8, alias: &str) -> Keyring {
        Keyring::new(
            Identity::from_secret([seed; 32]),
            alias,
            None,
            PeerKind::Person,
        )
    }

    fn friends() -> (Keyring, Keyring) {
        let mut a = station(1, "ana");
        let mut b = station(2, "bea");
        a.add_peer(b.card().clone(), true).unwrap();
        b.add_peer(a.card().clone(), true).unwrap();
        (a, b)
    }

    #[test]
    fn replay_window_tracks_out_of_order_and_old() {
        let mut w = ReplayWindow::default();
        assert_eq!(w.check(5), Seen::Fresh);
        w.commit(5);
        w.commit(3);
        assert_eq!(w.check(3), Seen::Again);
        assert_eq!(w.check(4), Seen::Fresh);
        w.commit(200);
        assert_eq!(w.check(5), Seen::TooOld);
        assert_eq!(w.check(199), Seen::Fresh);
        assert_eq!(w.check(200), Seen::Again);
    }

    #[test]
    fn pair_roundtrip_signed_and_encrypted() {
        let (mut a, mut b) = friends();
        let link = LinkRef::Peer(b.public());
        let s = a.seal(&link, MsgType::Text, false, b"hola").unwrap();
        assert_eq!(&s.wire[4..8], b"hola");
        let o = b.open(&s.wire).unwrap();
        assert_eq!(
            (o.body.as_slice(), o.sender, o.link),
            (&b"hola"[..], a.public(), LinkRef::Peer(a.public()))
        );
        let s = a.seal(&link, MsgType::Text, true, b"hola").unwrap();
        assert_ne!(&s.wire[4..8], b"hola");
        assert_eq!(b.open(&s.wire).unwrap().body, b"hola");
        assert!(matches!(
            b.open(&s.wire),
            Err(Error::Duplicate { counter: 1, .. })
        ));
    }

    #[test]
    fn tamper_and_strangers_fail() {
        let (mut a, mut b) = friends();
        let mut s = a
            .seal(&LinkRef::Peer(b.public()), MsgType::Text, false, b"x")
            .unwrap()
            .wire;
        s[4] ^= 1;
        assert_eq!(b.open(&s), Err(Error::AuthFailed));
        let mut c = station(3, "eve");
        c.add_peer(b.card().clone(), false).unwrap();
        let forged = c
            .seal(&LinkRef::Peer(b.public()), MsgType::Text, false, b"x")
            .unwrap();
        assert!(b.open(&forged.wire).is_err());
    }

    #[test]
    fn group_messages_and_join() {
        let gk = GroupKey([5; 32]);
        let (mut a, mut b) = friends();
        let id = a.add_group(gk.clone(), "Familia");
        b.add_group(gk.clone(), "Familia");
        b.add_group_member(&id, a.public(), "ana").unwrap();
        let g = LinkRef::Group(id);
        let s = a.seal(&g, MsgType::Text, true, b"cena").unwrap();
        let o = b.open(&s.wire).unwrap();
        assert_eq!(
            (o.link, o.sender, o.body.as_slice()),
            (g, a.public(), &b"cena"[..])
        );

        // A newcomer joins with the group key; members learn its key.
        let mut c = station(3, "carla");
        c.add_group(gk, "Familia");
        let body = [c.public().0.to_vec(), crate::textcodec::encode("carla")].concat();
        let join = c.seal(&g, MsgType::GroupJoin, false, &body).unwrap();
        b.open(&join.wire).unwrap();
        assert_eq!(
            b.group(&id).unwrap().roster.get(&c.public()).unwrap(),
            "carla"
        );
        let hi = c.seal(&g, MsgType::Text, false, b"hola").unwrap();
        assert_eq!(b.open(&hi.wire).unwrap().sender, c.public());
    }

    #[test]
    fn register_from_unknown_key_needs_acceptance() {
        let mut pharos = Keyring::new(
            Identity::from_secret([9; 32]),
            "casa",
            Some("EA5YYY"),
            PeerKind::Pharos,
        );
        let mut c = station(4, "mama");
        c.add_peer(pharos.card().clone(), true).unwrap();
        let body = [
            c.public().0.to_vec(),
            vec![0; 8],
            crate::textcodec::encode("mama"),
        ]
        .concat();
        let s = c
            .seal(
                &LinkRef::Peer(pharos.public()),
                MsgType::Register,
                false,
                &body,
            )
            .unwrap();
        let o = pharos.open(&s.wire).unwrap();
        assert!(o.new_peer);
        assert!(pharos.peer(&c.public()).is_none());
        pharos
            .accept_register(c.card().clone(), o.header.counter)
            .unwrap();
        assert!(matches!(pharos.open(&s.wire), Err(Error::Duplicate { .. })));
    }

    #[test]
    fn poll_and_acks() {
        let (mut a, mut b) = friends();
        let p = a.seal_poll(&b.public(), 30).unwrap();
        assert_eq!(p.wire.len(), POLL_LEN);
        let o = b.open(&p.wire).unwrap();
        assert_eq!(
            (o.header.msg_type, o.body.as_slice()),
            (MsgType::Poll, &[30u8][..])
        );
        let ack = b
            .ack(&o.link, &o.sender, o.header.counter, -21, 3, true)
            .unwrap();
        let link = LinkRef::Peer(b.public());
        let info = a.open_ack(&ack, &[(link, p.counter)]).unwrap();
        assert_eq!(
            (info.acker, info.snr_db, info.dt_ds, info.more),
            (b.public(), -21, 3, true)
        );
        // Same low byte, different counter: the full counter is bound in the tag.
        assert!(a.open_ack(&ack, &[(link, p.counter + 256)]).is_err());
    }

    #[test]
    fn blocked_peers_and_groups_are_dropped_unopened() {
        let (mut a, mut b) = friends();
        let ping = a.seal_poll(&b.public(), 0).unwrap();
        b.set_blocked(&LinkRef::Peer(a.public()), true).unwrap();
        assert_eq!(
            b.open(&ping.wire),
            Err(Error::UnknownSender),
            "no answer to pings"
        );
        b.set_blocked(&LinkRef::Peer(a.public()), false).unwrap();
        assert!(b.open(&ping.wire).is_ok());
        let gk = GroupKey([5; 32]);
        let id = a.add_group(gk.clone(), "Familia");
        b.add_group(gk, "Familia");
        b.add_group_member(&id, a.public(), "ana").unwrap();
        b.set_blocked(&LinkRef::Group(id), true).unwrap();
        let g = a
            .seal(&LinkRef::Group(id), MsgType::Text, false, b"hola")
            .unwrap();
        assert!(b.open(&g.wire).is_err());
    }

    #[test]
    fn restore_jumps_counters() {
        let (mut a, b) = friends();
        let link = LinkRef::Peer(b.public());
        a.seal(&link, MsgType::Text, false, b"1").unwrap();
        a.after_restore();
        assert_eq!(a.next_counter(&link).unwrap(), 1 + 4096);
    }
}
