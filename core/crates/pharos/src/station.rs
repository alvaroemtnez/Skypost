//! Pharos on-air logic with no I/O: frames and ticks in, effects out
//! (see `docs/spec/pharos.md` §1).
//!
//! The async shell in `main.rs` feeds received PHY data and clock ticks in and
//! carries out the returned effects in order (transmit, hand to bridges). It
//! must persist [`Station::state`] after every call, before transmitting.

use crate::access::{AccessState, Who};
use crate::outbox::{Outbox, Queued};
use crate::registry::{Client, Registry, Status};
use crate::router::RouterState;
use keryx::access::{AccessKey, PROOF_LEN};
use keryx::ack::ACK_LEN;
use keryx::admin::{Command as AdminCommand, Reply as AdminReply, Switch};
use keryx::arq::{ArqAction, ArqConfig, ArqSender};
use keryx::bridge::{Address, notice};
use keryx::header::{MsgType, peek_type};
use keryx::lane::{Lane, LanePolicy};
use keryx::message::{self, Received, flags};
use keryx::segment::frames_needed;
use keryx::{Card, Error, JS8_FRAME_PAYLOAD_BYTES, Keyring, LinkRef, Message, PeerKind, PublicKey};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::sync::Arc;

/// Reassembled data from the PHY plus what the decoder measured.
#[derive(Clone, Debug)]
pub struct RxFrame {
    pub data: Vec<u8>,
    pub snr_db: i8,
    /// Clock offset of the sender, deciseconds (+ = late).
    pub dt_ds: i8,
    /// Lane it arrived on; ACKs and announces answer on the same lane.
    pub lane: Lane,
    /// Unix time of reception.
    pub now: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Effect {
    /// Wire bytes to segment and send. `jitter`: wait a random 0–2 frame
    /// slots and pick a random audio offset first (answers to `Discover`).
    Transmit {
        wire: Vec<u8>,
        lane: Lane,
        jitter: bool,
    },
    /// A field user's message for a bridged contact.
    BridgeOut {
        from: PublicKey,
        alias: String,
        to: Address,
        message: Message,
    },
    /// SOS from a field user: alert its SOS contacts on every bridge.
    Sos {
        from: PublicKey,
        alias: String,
        message: Message,
    },
    /// Any other message addressed to the Pharos itself: logged.
    Inbox {
        from: PublicKey,
        alias: String,
        message: Message,
    },
    /// An admin command from a field user: run it with [`crate::admin::Admin`].
    Admin {
        from: PublicKey,
        encrypted: bool,
        command: AdminCommand,
    },
    /// Text for a bridged contact or admin (request answers, admin notices).
    BridgeText {
        to: Address,
        text: String,
    },
    /// A queued message reached the field user's station.
    Delivered {
        to: PublicKey,
        token: Option<u64>,
    },
    /// A queued message was dropped after the queue lifetime.
    Expired {
        to: PublicKey,
        token: Option<u64>,
    },
    Log(String),
}

#[derive(Clone)]
pub struct StationCfg {
    /// The access code, if one is set: a valid proof admits at once.
    pub code: Option<Arc<AccessKey>>,
    /// `Announce` flags for the bridges available (access bits are added).
    pub announce_flags: u8,
    /// Listen window assumed after a frame that is not a Poll, minutes.
    pub default_listen_min: u8,
    pub announce_every_secs: u64,
    pub queue_ttl_secs: u64,
    pub queue_max: usize,
    pub arq: ArqConfig,
    pub lanes: LanePolicy,
}

impl Default for StationCfg {
    fn default() -> Self {
        Self {
            code: None,
            announce_flags: flags::PHAROS,
            default_listen_min: 10,
            announce_every_secs: 10 * 60,
            queue_ttl_secs: 72 * 3600,
            queue_max: 50,
            arq: ArqConfig::default(),
            lanes: LanePolicy::default(),
        }
    }
}

/// Everything that must survive a restart. Holds key material.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct State {
    pub keyring: Keyring,
    #[serde(default)]
    pub registry: Registry,
    #[serde(default)]
    pub outbox: Outbox,
    /// Switches, admins and access requests (decision 0021).
    #[serde(default)]
    pub access: AccessState,
    /// The bridge bot's persisted part, kept here so one file holds it all.
    #[serde(default)]
    pub bot: RouterState,
    /// `base.toml` access settings were imported (first run only).
    #[serde(default)]
    pub imported: bool,
}

impl State {
    pub fn new(keyring: Keyring) -> Self {
        Self {
            keyring,
            registry: Registry::default(),
            outbox: Outbox::default(),
            access: AccessState::default(),
            bot: RouterState::default(),
            imported: false,
        }
    }
}

pub struct Station {
    state: State,
    cfg: StationCfg,
    arq: BTreeMap<PublicKey, ArqSender>,
    last_announce: Option<u64>,
}

impl Station {
    pub fn new(state: State, cfg: StationCfg) -> Self {
        Self {
            state,
            cfg,
            arq: BTreeMap::new(),
            last_announce: None,
        }
    }

    pub fn state(&self) -> &State {
        &self.state
    }

    pub fn registry(&self) -> &Registry {
        &self.state.registry
    }

    pub fn keyring(&self) -> &Keyring {
        &self.state.keyring
    }

    /// Put a field user on the allow list (from config, or a QR scanned at home).
    pub fn allow(&mut self, card: Card) -> Result<(), Error> {
        let key = card.key;
        let alias = card.alias.clone();
        self.state.keyring.add_peer(card, true)?;
        let c = match self.state.registry.get_mut(&key) {
            Some(c) => c,
            None => {
                self.state
                    .registry
                    .insert(key, Client::new(&alias, Status::Registered));
                self.state.registry.get_mut(&key).unwrap()
            }
        };
        c.status = Status::Registered;
        c.alias = alias;
        self.state.access.drop_request(&Who::Radio(key));
        Ok(())
    }

    /// Admin: ignore a field user completely (no ACKs, no delivery).
    pub fn block(&mut self, key: &PublicKey) -> Result<(), Error> {
        self.state
            .registry
            .get_mut(key)
            .ok_or(Error::UnknownLink)?
            .status = Status::Blocked;
        self.state.outbox.abandon(key);
        Ok(())
    }

    pub fn state_mut(&mut self) -> &mut State {
        &mut self.state
    }

    pub fn access(&self) -> &AccessState {
        &self.state.access
    }

    pub fn access_mut(&mut self) -> &mut AccessState {
        &mut self.state.access
    }

    /// Flip a switch. Turning emergency off lets the next emergency tell
    /// plain senders again.
    pub fn set_switch(&mut self, s: Switch, on: bool) {
        self.state.access.switches.set(s, on);
        if s == Switch::Emergency && !on {
            let keys: Vec<PublicKey> = self.state.registry.iter().map(|(k, _)| *k).collect();
            for k in keys {
                if let Some(c) = self.state.registry.get_mut(&k) {
                    c.told_encrypted_only = false;
                }
            }
        }
    }

    pub fn has_code(&self) -> bool {
        self.cfg.code.is_some()
    }

    /// A bridged contact asks for access (closed, requests on). The answer
    /// for them, in Spanish, goes out as [`Effect::BridgeText`].
    pub fn request_contact(&mut self, from: &Address, name: &str, now: u64) -> Vec<Effect> {
        let terse = matches!(from, Address::MeshcoreContact(_));
        let text = match self.state.access.add_request(
            Who::Contact(from.clone()),
            name.to_owned(),
            now,
        ) {
            Some(_) if terse => "Solicitud enviada a los administradores.",
            Some(_) => {
                "Este Pharos es privado. He enviado tu solicitud a sus administradores; te aviso cuando la acepten. Si tienes su código de acceso, envíamelo."
            }
            None => "Este Pharos no admite más solicitudes ahora. Prueba más tarde.",
        };
        let mut out = vec![Effect::BridgeText {
            to: from.clone(),
            text: text.into(),
        }];
        out.extend(self.notices(now));
        out
    }

    /// Hourly "new requests" notices to admins that are due.
    fn notices(&mut self, now: u64) -> Vec<Effect> {
        let pending = self.state.access.requests.len().min(255) as u8;
        let mut out = Vec::new();
        for (who, networks) in self.state.access.due_notices(now) {
            match who {
                Who::Radio(k) => {
                    let m = Message::AdminReply(AdminReply::NewRequests { networks, pending });
                    out.extend(self.queue(&k, m, None, now).unwrap_or_default());
                }
                Who::Contact(to) => out.push(Effect::BridgeText {
                    to,
                    text: format!(
                        "New access request ({pending} pending). Reply /admin requests to see them."
                    ),
                }),
            }
        }
        out
    }

    /// Admin: lift a block; the user is registered again.
    pub fn unblock(&mut self, key: &PublicKey) -> Result<(), Error> {
        self.state
            .registry
            .get_mut(key)
            .ok_or(Error::UnknownLink)?
            .status = Status::Registered;
        Ok(())
    }

    /// The operator accepted a pending registration.
    pub fn approve(&mut self, key: &PublicKey, now: u64) -> Result<Vec<Effect>, Error> {
        let c = self.state.registry.get(key).ok_or(Error::UnknownLink)?;
        let code = if self.state.registry.alias_taken(&c.alias.clone(), key) {
            notice::ALIAS_TAKEN
        } else {
            self.state.registry.get_mut(key).unwrap().status = Status::Registered;
            notice::REGISTERED
        };
        self.state.access.drop_request(&Who::Radio(*key));
        self.notice(key, code, None, now)
    }

    pub fn refuse(&mut self, key: &PublicKey, now: u64) -> Result<Vec<Effect>, Error> {
        self.state
            .registry
            .get_mut(key)
            .ok_or(Error::UnknownLink)?
            .status = Status::Refused;
        self.state.access.drop_request(&Who::Radio(*key));
        self.notice(key, notice::REGISTRATION_REFUSED, None, now)
    }

    fn notice(
        &mut self,
        to: &PublicKey,
        code: u8,
        handle: Option<u8>,
        now: u64,
    ) -> Result<Vec<Effect>, Error> {
        self.queue(to, Message::Notice { code, handle }, None, now)
    }

    /// Queue a message for a field user; it goes out while the user listens.
    pub fn queue(
        &mut self,
        to: &PublicKey,
        message: Message,
        token: Option<u64>,
        now: u64,
    ) -> Result<Vec<Effect>, Error> {
        if self.state.registry.get(to).is_none() {
            return Err(Error::UnknownLink);
        }
        if self.state.outbox.len(to) >= self.cfg.queue_max {
            return Err(Error::Range("queue full"));
        }
        self.state.outbox.push(
            *to,
            Queued {
                message,
                queued_at: now,
                token,
            },
        );
        Ok(self.send_next(to, now).into_iter().collect())
    }

    /// Queue a message from a bridged contact, binding a handle first if the
    /// field user has none for that address.
    pub fn queue_bridged(
        &mut self,
        to: &PublicKey,
        from: &Address,
        name: Option<String>,
        inner: Message,
        token: Option<u64>,
        now: u64,
    ) -> Result<Vec<Effect>, Error> {
        let client = self.state.registry.get_mut(to).ok_or(Error::UnknownLink)?;
        let (handle, new) = client.ensure_handle(from);
        let mut out = Vec::new();
        if new {
            let open = Message::BridgeOpen {
                handle,
                address: from.clone(),
                name,
            };
            out.extend(self.queue(to, open, None, now)?);
        }
        let msg = Message::Bridged {
            handle,
            inner: Box::new(inner),
        };
        out.extend(self.queue(to, msg, token, now)?);
        Ok(out)
    }

    pub fn on_rx(&mut self, f: &RxFrame) -> Vec<Effect> {
        match self.handle(f) {
            Ok(effects) => effects,
            Err(e) => vec![Effect::Log(format!("dropped frame: {e}"))],
        }
    }

    /// Call every few seconds: ARQ retries and queue expiry.
    pub fn on_tick(&mut self, now: u64) -> Vec<Effect> {
        let mut out = Vec::new();
        let keys: Vec<PublicKey> = self.arq.keys().copied().collect();
        for key in keys {
            match self.arq.get_mut(&key).and_then(|a| a.on_tick(now)) {
                Some(ArqAction::Transmit { lane, attempt, .. }) => {
                    if let Some((_, wire)) = self.state.outbox.in_flight(&key) {
                        out.push(Effect::Log(format!("retry {attempt} on {lane:?}")));
                        out.push(Effect::Transmit {
                            wire: wire.clone(),
                            lane,
                            jitter: false,
                        });
                    }
                }
                Some(ArqAction::Failed { .. }) => {
                    // Not hearing us: hold the queue until we hear it again.
                    self.state.outbox.abandon(&key);
                    if let Some(c) = self.state.registry.get_mut(&key) {
                        c.listen_until = 0;
                    }
                    out.push(Effect::Log(
                        "delivery failed; holding until the user is heard".into(),
                    ));
                }
                _ => {}
            }
        }
        let cutoff = now.saturating_sub(self.cfg.queue_ttl_secs);
        for (to, q) in self.state.outbox.expire(cutoff) {
            out.push(Effect::Expired { to, token: q.token });
        }
        for r in self.state.access.expire(now) {
            if let Who::Radio(k) = r.who
                && let Some(c) = self.state.registry.get_mut(&k)
                && c.status == Status::Pending
            {
                c.status = Status::Unregistered;
            }
            out.push(Effect::Log(format!("access request #{} expired", r.num)));
        }
        out.extend(self.notices(now));
        out
    }

    fn ack(
        &self,
        link: &LinkRef,
        to: &PublicKey,
        counter: u16,
        f: &RxFrame,
    ) -> Result<Effect, Error> {
        let more = self.state.outbox.len(to) > 0;
        let wire = self
            .state
            .keyring
            .ack(link, to, counter, f.snr_db, f.dt_ds, more)?;
        Ok(Effect::Transmit {
            wire: wire.to_vec(),
            lane: f.lane,
            jitter: false,
        })
    }

    fn send_next(&mut self, to: &PublicKey, now: u64) -> Option<Effect> {
        if self.state.outbox.in_flight(to).is_some()
            || self.arq.get(to).is_some_and(ArqSender::is_busy)
        {
            return None;
        }
        let client = self.state.registry.get(to)?;
        if !client.is_listening(now) {
            return None;
        }
        let encrypt = client.encrypts;
        let q = self.state.outbox.peek(to)?.clone();
        let sealed = match message::encode(
            &mut self.state.keyring,
            &q.message,
            &LinkRef::Peer(*to),
            encrypt,
        ) {
            Ok(s) => s,
            Err(e) => return Some(Effect::Log(format!("cannot seal queued message: {e}"))),
        };
        let frames = frames_needed(sealed.wire.len(), JS8_FRAME_PAYLOAD_BYTES) as u32;
        let (arq_cfg, lanes) = (self.cfg.arq, self.cfg.lanes);
        let arq = self
            .arq
            .entry(*to)
            .or_insert_with(|| ArqSender::new(arq_cfg, lanes));
        let Ok(ArqAction::Transmit { lane, .. }) = arq.send(sealed.counter, frames, now) else {
            return None;
        };
        self.state
            .outbox
            .set_in_flight(*to, sealed.counter, sealed.wire.clone());
        Some(Effect::Transmit {
            wire: sealed.wire,
            lane,
            jitter: false,
        })
    }

    fn heard(&mut self, key: &PublicKey, now: u64, listen_min: Option<u8>) {
        let default = u64::from(self.cfg.default_listen_min) * 60;
        if let Some(c) = self.state.registry.get_mut(key) {
            c.last_heard = now;
            c.listen_until = match listen_min {
                Some(m) => now + u64::from(m) * 60,
                None => c.listen_until.max(now + default),
            };
        }
    }

    fn handle(&mut self, f: &RxFrame) -> Result<Vec<Effect>, Error> {
        match peek_type(&f.data)? {
            MsgType::Ack => self.on_ack(f),
            MsgType::Discover | MsgType::Announce => self.on_unauthenticated(f),
            _ => self.on_message(f),
        }
    }

    fn on_ack(&mut self, f: &RxFrame) -> Result<Vec<Effect>, Error> {
        let waiting: Vec<(LinkRef, u16)> = self
            .state
            .outbox
            .all_in_flight()
            .map(|(k, c)| (LinkRef::Peer(*k), c))
            .collect();
        let a = self
            .state
            .keyring
            .open_ack(&f.data[..ACK_LEN.min(f.data.len())], &waiting)?;
        let LinkRef::Peer(key) = a.link else {
            return Ok(vec![]);
        };
        self.heard(&key, f.now, None);
        let mut out = Vec::new();
        let acked = self
            .arq
            .get_mut(&key)
            .and_then(|arq| arq.on_ack(a.counter, a.snr_db, a.dt_ds, f.now));
        if acked.is_some()
            && let Some(q) = self.state.outbox.delivered(&key, a.counter)
        {
            out.push(Effect::Delivered {
                to: key,
                token: q.token,
            });
        }
        out.extend(self.send_next(&key, f.now));
        Ok(out)
    }

    fn on_unauthenticated(&mut self, f: &RxFrame) -> Result<Vec<Effect>, Error> {
        match message::decode(&mut self.state.keyring, &f.data)? {
            Received::Discover { src, query } => {
                if self
                    .last_announce
                    .is_some_and(|t| f.now < t + self.cfg.announce_every_secs)
                {
                    return Ok(vec![Effect::Log(
                        "discover ignored: announced recently".into(),
                    )]);
                }
                let a = &self.state.access;
                if a.on(Switch::Stealth) {
                    return Ok(vec![Effect::Log("discover ignored: stealth".into())]);
                }
                let closed = !a.on(Switch::Open);
                let mut fl = self.cfg.announce_flags | flags::PHAROS;
                if closed {
                    fl |= flags::CLOSED;
                    if a.on(Switch::Requests) {
                        fl |= flags::REQUESTS;
                    }
                    if self.cfg.code.is_some() {
                        fl |= flags::CODE_REQUIRED;
                    }
                }
                self.last_announce = Some(f.now);
                let wire = message::encode_announce(&self.state.keyring, fl, src, query)?;
                Ok(vec![Effect::Transmit {
                    wire,
                    lane: f.lane,
                    jitter: true,
                }])
            }
            Received::Announce { card, .. } => Ok(vec![Effect::Log(format!(
                "heard announce of {}",
                card.alias
            ))]),
            Received::Message(_) => Err(Error::Malformed("unexpected authenticated frame")),
        }
    }

    fn on_message(&mut self, f: &RxFrame) -> Result<Vec<Effect>, Error> {
        let d = match message::decode(&mut self.state.keyring, &f.data) {
            Ok(Received::Message(d)) => d,
            Ok(_) => return Err(Error::Malformed("unexpected unauthenticated frame")),
            Err(Error::Duplicate {
                link,
                sender,
                counter,
            }) => {
                return Ok(vec![
                    Effect::Log("duplicate, re-ACKing".into()),
                    self.ack(&link, &sender, counter, f)?,
                ]);
            }
            Err(e) => return Err(e),
        };
        let LinkRef::Peer(key) = d.link else {
            return Ok(vec![Effect::Log("group message ignored".into())]);
        };
        if self
            .state
            .registry
            .get(&key)
            .is_some_and(|c| c.status == Status::Blocked)
        {
            return Ok(vec![Effect::Log(
                "frame from a blocked client ignored".into(),
            )]);
        }
        if let Message::Register { alias, proof, .. } = &d.message {
            return self.on_register(&d, alias.clone(), proof, f);
        }
        let Some(client) = self.state.registry.get_mut(&key) else {
            return Ok(vec![Effect::Log(
                "frame from a peer that is not a client".into(),
            )]);
        };
        client.encrypts = d.header.encrypted;
        let registered = client.status == Status::Registered;
        let alias = client.alias.clone();
        let encrypted = d.header.encrypted;
        let listen = match d.message {
            Message::Poll { listen_min } => Some(listen_min),
            _ => None,
        };
        self.heard(&key, f.now, listen);
        // ACKed even when dropped below: no ACK means four retries on air.
        let mut out = vec![self.ack(&d.link, &key, d.header.counter, f)?];
        if self.state.access.on(Switch::Emergency) && !encrypted && !allowed_in_clear(&d.message) {
            out.push(Effect::Log(format!(
                "@{alias}: plain message dropped (emergency)"
            )));
            let c = self.state.registry.get_mut(&key).unwrap();
            if !c.told_encrypted_only && !self.state.access.on(Switch::Stealth) {
                c.told_encrypted_only = true;
                c.encrypts = true;
                out.extend(self.notice(&key, notice::ENCRYPTED_ONLY, None, f.now)?);
            }
            return Ok(out);
        }
        if let Message::Admin(command) = d.message {
            // Admin commands also come from users who are not registered (a
            // pending admin can still log in); [`crate::admin`] decides.
            out.push(Effect::Admin {
                from: key,
                encrypted,
                command,
            });
            out.extend(self.send_next(&key, f.now));
            return Ok(out);
        }
        if !registered {
            out.push(Effect::Log(format!(
                "@{alias} is not registered; nothing delivered"
            )));
            return Ok(out);
        }
        match d.message {
            Message::Poll { .. } => {}
            Message::BridgeOpen {
                handle, address, ..
            } => {
                let c = self.state.registry.get_mut(&key).unwrap();
                c.handles.insert(handle, address);
            }
            Message::Bridged { handle, inner } => {
                match self
                    .state
                    .registry
                    .get(&key)
                    .unwrap()
                    .handles
                    .get(&handle)
                    .cloned()
                {
                    Some(to) => out.push(Effect::BridgeOut {
                        from: key,
                        alias,
                        to,
                        message: *inner,
                    }),
                    None => out.extend(self.notice(
                        &key,
                        notice::UNKNOWN_HANDLE,
                        Some(handle),
                        f.now,
                    )?),
                }
            }
            m @ Message::Sos(_) => out.push(Effect::Sos {
                from: key,
                alias,
                message: m,
            }),
            m => out.push(Effect::Inbox {
                from: key,
                alias,
                message: m,
            }),
        }
        out.extend(self.send_next(&key, f.now));
        Ok(out)
    }

    fn on_register(
        &mut self,
        d: &keryx::Decoded,
        alias: String,
        proof: &[u8; PROOF_LEN],
        f: &RxFrame,
    ) -> Result<Vec<Effect>, Error> {
        let key = d.sender;
        if d.new_peer {
            let card = Card {
                key,
                alias: alias.clone(),
                callsign: None,
                kind: PeerKind::Person,
            };
            self.state.keyring.accept_register(card, d.header.counter)?;
        }
        let taken = self.state.registry.alias_taken(&alias, &key);
        let mut out = Vec::new();
        let code = match self.state.registry.get(&key).map(|c| c.status) {
            Some(Status::Registered) if taken => notice::ALIAS_TAKEN,
            Some(Status::Registered) => {
                self.state.registry.get_mut(&key).unwrap().alias = alias;
                notice::REGISTERED
            }
            Some(Status::Pending) => notice::REGISTRATION_PENDING,
            Some(Status::Refused) => notice::REGISTRATION_REFUSED,
            Some(Status::Blocked) => unreachable!("blocked clients are filtered earlier"),
            None | Some(Status::Unregistered) => {
                let pharos = self.state.keyring.public();
                let a = &self.state.access;
                let code_ok = self
                    .cfg
                    .code
                    .as_ref()
                    .is_some_and(|k| k.verify(&key, &pharos, proof));
                let (status, code) = if taken {
                    (Status::Unregistered, notice::ALIAS_TAKEN)
                } else if a.on(Switch::Open) || code_ok {
                    (Status::Registered, notice::REGISTERED)
                } else if a.on(Switch::Requests) {
                    match self
                        .state
                        .access
                        .add_request(Who::Radio(key), alias.clone(), f.now)
                    {
                        Some(_) => {
                            out.extend(self.notices(f.now));
                            (Status::Pending, notice::REGISTRATION_PENDING)
                        }
                        None => (Status::Unregistered, notice::REGISTRATION_REFUSED),
                    }
                } else if self.cfg.code.is_some() {
                    (Status::Unregistered, notice::ACCESS_CODE_NEEDED)
                } else {
                    (Status::Unregistered, notice::REGISTRATION_REFUSED)
                };
                let c = Client::new(&alias, status);
                match self.state.registry.get_mut(&key) {
                    Some(old) => {
                        old.alias = c.alias;
                        old.status = c.status;
                    }
                    None => self.state.registry.insert(key, c),
                }
                code
            }
        };
        self.state.registry.get_mut(&key).unwrap().encrypts = false;
        self.heard(&key, f.now, None);
        out.insert(0, self.ack(&d.link, &key, d.header.counter, f)?);
        out.extend(self.notice(&key, code, None, f.now)?);
        Ok(out)
    }
}

/// What a Pharos in emergency mode still takes in signed plaintext: SOS,
/// pings, access requests and the emergency switch itself.
fn allowed_in_clear(m: &Message) -> bool {
    matches!(
        m,
        Message::Sos(_)
            | Message::Poll { .. }
            | Message::Register { .. }
            | Message::Admin(AdminCommand::Set(Switch::Emergency, _))
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use keryx::JS8_FRAME_PAYLOAD_BYTES as MTU;
    use keryx::bridge::Address;
    use keryx::position::Position;
    use keryx::segment::{reassemble, segment};
    use keryx::{Identity, Keyring};

    const T0: u64 = 1_800_000_000;

    fn rx(wire: &[u8], now: u64) -> RxFrame {
        RxFrame {
            data: reassemble(&segment(wire, MTU)),
            snr_db: -14,
            dt_ds: 2,
            lane: Lane::Js8Normal,
            now,
        }
    }

    #[derive(Clone, Copy)]
    enum Access {
        Open,
        /// Closed, taking requests (the default).
        Closed,
    }

    fn pharos(access: Access) -> Station {
        let k = Keyring::new(
            Identity::from_secret([9; 32]),
            "casa",
            Some("EA5YYY"),
            PeerKind::Pharos,
        );
        let mut st = State::new(k);
        st.access
            .switches
            .set(Switch::Open, matches!(access, Access::Open));
        Station::new(st, StationCfg::default())
    }

    fn field(seed: u8, alias: &str, p: &Station) -> Keyring {
        let mut k = Keyring::new(
            Identity::from_secret([seed; 32]),
            alias,
            None,
            PeerKind::Person,
        );
        k.add_peer(p.keyring().card().clone(), true).unwrap();
        k
    }

    fn send(f: &mut Keyring, p: &Station, m: Message, enc: bool) -> Vec<u8> {
        message::encode(f, &m, &LinkRef::Peer(p.keyring().public()), enc)
            .unwrap()
            .wire
    }

    fn send_register(k: &mut Keyring, p: &Station) -> Vec<u8> {
        send_register_with(k, p, keryx::access::NO_PROOF)
    }

    fn send_register_with(k: &mut Keyring, p: &Station, proof: [u8; PROOF_LEN]) -> Vec<u8> {
        let m = Message::Register {
            key: k.public(),
            proof,
            alias: k.card().alias.clone(),
        };
        send(k, p, m, false)
    }

    fn transmits(fx: &[Effect]) -> Vec<Vec<u8>> {
        fx.iter()
            .filter_map(|e| match e {
                Effect::Transmit { wire, .. } => Some(wire.clone()),
                _ => None,
            })
            .collect()
    }

    fn open(f: &mut Keyring, wire: &[u8]) -> Message {
        match message::decode(f, wire).unwrap() {
            Received::Message(d) => d.message,
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn allowlisted_client_bridges_and_gets_acks() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        let wa = Address::WhatsappPhone("34600123456".into());
        let open_chat = send(
            &mut ana,
            &p,
            Message::BridgeOpen {
                handle: 0,
                address: wa.clone(),
                name: None,
            },
            false,
        );
        let fx = p.on_rx(&rx(&open_chat, T0));
        assert_eq!(transmits(&fx).len(), 1, "just the ACK: {fx:?}");
        let hi = send(
            &mut ana,
            &p,
            Message::Bridged {
                handle: 0,
                inner: Box::new(Message::Text("Hola mamá".into())),
            },
            false,
        );
        let fx = p.on_rx(&rx(&hi, T0 + 60));
        let ack = &transmits(&fx)[0];
        let info = ana
            .open_ack(ack, &[(LinkRef::Peer(p.keyring().public()), 1)])
            .unwrap();
        assert_eq!((info.snr_db, info.more), (-14, false));
        assert!(fx.iter().any(|e| matches!(e,
            Effect::BridgeOut { to, message: Message::Text(t), alias, .. } if *to == wa && t == "Hola mamá" && alias == "ana")));
        // Duplicate: re-ACK only.
        let fx = p.on_rx(&rx(&hi, T0 + 90));
        assert_eq!(transmits(&fx).len(), 1);
        assert!(!fx.iter().any(|e| matches!(e, Effect::BridgeOut { .. })));
    }

    #[test]
    fn emergency_drops_plain_messages_but_acks_and_tells_once() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        p.set_switch(Switch::Emergency, true);
        let hi = send(&mut ana, &p, Message::Text("hola".into()), false);
        let fx = p.on_rx(&rx(&hi, T0));
        let tx = transmits(&fx);
        assert_eq!(tx.len(), 2, "ACK, then the notice: {fx:?}");
        let d = message::decode(&mut ana, &tx[1]).unwrap();
        let Received::Message(d) = d else { panic!() };
        assert!(d.header.encrypted, "the notice goes encrypted");
        assert_eq!(
            d.message,
            Message::Notice {
                code: notice::ENCRYPTED_ONLY,
                handle: None
            }
        );
        // Second plain message: ACK only. SOS still passes.
        let hi = send(&mut ana, &p, Message::Text("hola".into()), false);
        assert_eq!(transmits(&p.on_rx(&rx(&hi, T0 + 60))).len(), 1);
        let sos = send(&mut ana, &p, Message::Sos(None), false);
        assert!(
            p.on_rx(&rx(&sos, T0 + 90))
                .iter()
                .any(|e| matches!(e, Effect::Sos { .. }))
        );
        let enc = send(&mut ana, &p, Message::Text("hola".into()), true);
        assert!(
            p.on_rx(&rx(&enc, T0 + 120))
                .iter()
                .any(|e| matches!(e, Effect::Inbox { .. }))
        );
    }

    #[test]
    fn requests_from_the_radio_notify_admins_and_expire() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        p.access_mut()
            .add_admin(Who::Radio(ana.public()), keryx::admin::how::SSH);
        let poll = send(&mut ana, &p, Message::Poll { listen_min: 30 }, false);
        p.on_rx(&rx(&poll, T0));
        let mut bea = field(3, "bea", &p);
        let fx = p.on_rx(&rx(&send_register(&mut bea, &p), T0 + 10));
        assert_eq!(p.access().requests.len(), 1);
        assert_eq!(p.access().requests[0].name, "bea");
        // The ACK to bea and bea's notice, plus one notice for ana.
        let to_ana: Vec<Message> = transmits(&fx)
            .iter()
            .filter_map(|w| match message::decode(&mut ana, w) {
                Ok(Received::Message(d)) => Some(d.message),
                _ => None,
            })
            .collect();
        assert_eq!(
            to_ana,
            vec![Message::AdminReply(AdminReply::NewRequests {
                networks: keryx::admin::network_bit(keryx::admin::who::RADIO),
                pending: 1
            })]
        );
        p.access_mut().request_ttl_secs = 3600;
        p.on_tick(T0 + 4000);
        assert!(p.access().requests.is_empty());
        assert_eq!(
            p.registry().get(&bea.public()).unwrap().status,
            Status::Unregistered
        );
    }

    #[test]
    fn stealth_answers_no_discovery() {
        let mut p = pharos(Access::Closed);
        p.set_switch(Switch::Stealth, true);
        let ana = field(1, "ana", &p);
        let q = message::encode_discover(&ana, 77).unwrap();
        assert!(transmits(&p.on_rx(&rx(&q, T0))).is_empty());
    }

    #[test]
    fn requests_off_and_no_code_refuse_registration() {
        let mut p = pharos(Access::Closed);
        p.set_switch(Switch::Requests, false);
        let mut eve = field(2, "eve", &p);
        let tx = transmits(&p.on_rx(&rx(&send_register(&mut eve, &p), T0)));
        assert_eq!(
            open(&mut eve, &tx[1]),
            Message::Notice {
                code: notice::REGISTRATION_REFUSED,
                handle: None
            }
        );
        assert!(p.access().requests.is_empty());
    }

    #[test]
    fn access_code_mode_admits_only_with_the_proof() {
        let k = Keyring::new(
            Identity::from_secret([9; 32]),
            "casa",
            Some("EA5YYY"),
            PeerKind::Pharos,
        );
        let code = Arc::new(AccessKey::derive("caballo pila grapa", &k.public()).unwrap());
        let mut st = State::new(k);
        st.access.switches.set(Switch::Requests, false);
        let mut p = Station::new(
            st,
            StationCfg {
                code: Some(code.clone()),
                ..StationCfg::default()
            },
        );
        let mut eve = field(2, "eve", &p);
        let tx = transmits(&p.on_rx(&rx(&send_register(&mut eve, &p), T0)));
        assert_eq!(tx.len(), 2, "ACK then the notice (eve is listening)");
        assert_eq!(
            open(&mut eve, &tx[1]),
            Message::Notice {
                code: notice::ACCESS_CODE_NEEDED,
                handle: None
            }
        );
        let hi = send(&mut eve, &p, Message::Text("hola".into()), false);
        assert!(
            !p.on_rx(&rx(&hi, T0 + 1))
                .iter()
                .any(|e| matches!(e, Effect::Inbox { .. }))
        );

        // Retry with the right code (eve ACKs the notice first).
        let link = LinkRef::Peer(p.keyring().public());
        let ack = eve
            .ack(&link, &p.keyring().public(), 0, -10, 0, false)
            .unwrap();
        p.on_rx(&rx(&ack, T0 + 2));
        let proof = code.proof(&eve.public(), &p.keyring().public());
        let tx = transmits(&p.on_rx(&rx(&send_register_with(&mut eve, &p, proof), T0 + 3)));
        assert_eq!(
            open(&mut eve, &tx[1]),
            Message::Notice {
                code: notice::REGISTERED,
                handle: None
            }
        );
    }

    #[test]
    fn blocked_clients_get_nothing() {
        let mut p = pharos(Access::Open);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        p.block(&ana.public()).unwrap();
        let hi = send(&mut ana, &p, Message::Text("hola".into()), false);
        assert!(transmits(&p.on_rx(&rx(&hi, T0))).is_empty());
        p.unblock(&ana.public()).unwrap();
        let hi = send(&mut ana, &p, Message::Text("hola".into()), false);
        assert_eq!(transmits(&p.on_rx(&rx(&hi, T0 + 1))).len(), 1);
    }

    #[test]
    fn approval_flow_and_alias_clash() {
        let mut p = pharos(Access::Closed);
        let ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        let mut bea = field(3, "bea", &p);
        let reg = send_register(&mut bea, &p);
        let fx = p.on_rx(&rx(&reg, T0));
        assert!(p.access().request_of(&Who::Radio(bea.public())).is_some());
        let tx = transmits(&fx);
        assert_eq!(
            open(&mut bea, &tx[1]),
            Message::Notice {
                code: notice::REGISTRATION_PENDING,
                handle: None
            }
        );
        // Bea ACKs the notice, then the operator approves.
        let ack = bea
            .ack(
                &LinkRef::Peer(p.keyring().public()),
                &p.keyring().public(),
                0,
                -10,
                0,
                false,
            )
            .unwrap();
        p.on_rx(&rx(&ack, T0 + 30));
        let fx = p.approve(&bea.public(), T0 + 60).unwrap();
        assert_eq!(
            open(&mut bea, &transmits(&fx)[0]),
            Message::Notice {
                code: notice::REGISTERED,
                handle: None
            }
        );
        assert!(p.access().requests.is_empty(), "approving ends the request");

        let mut clash = field(4, "Ana", &p);
        let reg = send_register(&mut clash, &p);
        let tx = transmits(&p.on_rx(&rx(&reg, T0 + 70)));
        assert_eq!(
            open(&mut clash, &tx[1]),
            Message::Notice {
                code: notice::ALIAS_TAKEN,
                handle: None
            }
        );
    }

    #[test]
    fn store_and_forward_follows_the_listen_window() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        let mama = Address::SignalPhone("34611111111".into());
        // Not heard yet: everything waits.
        let fx = p
            .queue_bridged(
                &ana.public(),
                &mama,
                Some("Mamá".into()),
                Message::Text("¿Qué tal?".into()),
                Some(7),
                T0,
            )
            .unwrap();
        assert!(transmits(&fx).is_empty());
        // A ping with a 30-minute window: ACK says "more", then the BridgeOpen.
        let ping = send(&mut ana, &p, Message::Poll { listen_min: 30 }, false);
        let fx = p.on_rx(&rx(&ping, T0 + 100));
        let tx = transmits(&fx);
        let pharos_link = LinkRef::Peer(p.keyring().public());
        assert!(ana.open_ack(&tx[0], &[(pharos_link, 0)]).unwrap().more);
        assert!(matches!(
            open(&mut ana, &tx[1]),
            Message::BridgeOpen { handle: 128, .. }
        ));
        // Each ACK releases the next one.
        let ack = ana
            .ack(&pharos_link, &p.keyring().public(), 0, -12, 0, false)
            .unwrap();
        let fx = p.on_rx(&rx(&ack, T0 + 160));
        let Message::Bridged { handle: 128, inner } = open(&mut ana, &transmits(&fx)[0]) else {
            panic!()
        };
        assert_eq!(*inner, Message::Text("¿Qué tal?".into()));
        let ack = ana
            .ack(&pharos_link, &p.keyring().public(), 1, -12, 0, false)
            .unwrap();
        let fx = p.on_rx(&rx(&ack, T0 + 220));
        assert!(fx.contains(&Effect::Delivered {
            to: ana.public(),
            token: Some(7)
        }));
    }

    #[test]
    fn retries_then_holds_and_expires() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        let ping = send(&mut ana, &p, Message::Poll { listen_min: 60 }, false);
        p.on_rx(&rx(&ping, T0));
        let fx = p
            .queue(&ana.public(), Message::Canned(0), Some(1), T0 + 10)
            .unwrap();
        assert_eq!(transmits(&fx).len(), 1);
        let mut now = T0 + 10;
        let mut sent = 1;
        let mut failed = false;
        while !failed {
            now += 30;
            for e in p.on_tick(now) {
                match e {
                    Effect::Transmit { .. } => sent += 1,
                    Effect::Log(l) if l.starts_with("delivery failed") => failed = true,
                    _ => {}
                }
            }
        }
        assert_eq!(sent, 4);
        assert!(!p.registry().get(&ana.public()).unwrap().is_listening(now));
        let fx = p.on_tick(now + 72 * 3600);
        assert!(fx.contains(&Effect::Expired {
            to: ana.public(),
            token: Some(1)
        }));
    }

    #[test]
    fn sos_unknown_handle_and_mirrored_mode() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        p.allow(ana.card().clone()).unwrap();
        let sos = send(
            &mut ana,
            &p,
            Message::Sos(Some(Position {
                lat: 42.6,
                lon: 0.6,
            })),
            true,
        );
        let fx = p.on_rx(&rx(&sos, T0));
        assert!(
            fx.iter()
                .any(|e| matches!(e, Effect::Sos { alias, .. } if alias == "ana"))
        );
        let orphan = send(
            &mut ana,
            &p,
            Message::Bridged {
                handle: 5,
                inner: Box::new(Message::Canned(0)),
            },
            true,
        );
        let tx = transmits(&p.on_rx(&rx(&orphan, T0 + 60)));
        let Received::Message(d) = message::decode(&mut ana, &tx[1]).unwrap() else {
            panic!()
        };
        assert!(d.header.encrypted, "Pharos mirrors the emergency mode");
        assert_eq!(
            d.message,
            Message::Notice {
                code: notice::UNKNOWN_HANDLE,
                handle: Some(5)
            }
        );
    }

    #[test]
    fn discover_gets_one_announce_per_ten_minutes() {
        let mut p = pharos(Access::Closed);
        let mut ana = field(1, "ana", &p);
        let q = message::encode_discover(&ana, 77).unwrap();
        let fx = p.on_rx(&rx(&q, T0));
        let Effect::Transmit {
            wire, jitter: true, ..
        } = &fx[0]
        else {
            panic!("{fx:?}")
        };
        let Received::Announce {
            card,
            flags: f,
            query: 77,
            ..
        } = message::decode(&mut ana, wire).unwrap()
        else {
            panic!()
        };
        assert_eq!(card.alias, "casa");
        assert_ne!(f & flags::CLOSED, 0);
        assert_ne!(f & flags::REQUESTS, 0);
        assert_eq!(f & flags::CODE_REQUIRED, 0);
        assert!(transmits(&p.on_rx(&rx(&q, T0 + 60))).is_empty());
        assert_eq!(transmits(&p.on_rx(&rx(&q, T0 + 601))).len(), 1);
    }
}
