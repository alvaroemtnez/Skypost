//! The bridge bot: routes messages from Signal/WhatsApp/MeshCore contacts to
//! field users by alias, and formats field messages for them (see
//! `docs/spec/pharos.md` §2). Pure logic: the shell feeds events in and
//! carries out the returned actions. Bot texts are Spanish.
//!
//! One-to-one chats only. Every refusal is answered, so nobody believes an
//! undelivered message went out.

use crate::admin::{self, Command};
use keryx::bridge::Address;
use keryx::{Message, PublicKey};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// What the router needs to know about field users.
pub trait Directory {
    /// Registered field user with this alias (case-insensitive, no `@`).
    fn by_alias(&self, alias: &str) -> Option<PublicKey>;
    fn alias_of(&self, key: &PublicKey) -> Option<String>;
    /// A field user lists this contact in the Pharos config.
    fn listed(&self, from: &Address) -> bool;
    fn is_listening(&self, key: &PublicKey, now: u64) -> bool;
}

/// Who on the bridged networks may use the bot.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Inbound {
    /// Authorised contacts, plus whoever sends the access code.
    #[default]
    Code,
    /// Authorised contacts only: listed in the config, written to first by a
    /// field user, or allowed by an admin.
    Contacts,
    Anyone,
}

#[derive(Clone, Debug)]
pub struct RouterCfg {
    pub inbound: Inbound,
    /// The Pharos access code (`Inbound::Code`).
    pub access_code: Option<String>,
    /// Admin accounts: their commands go to [`crate::admin`].
    pub admins: Vec<Address>,
    pub target_timeout_secs: u64,
    pub max_chars: usize,
    pub max_held: usize,
    pub max_queued_per_contact: usize,
    /// Wrong codes per contact per hour before the bot stops answering.
    pub max_code_attempts: usize,
}

impl Default for RouterCfg {
    fn default() -> Self {
        Self {
            inbound: Inbound::Code,
            access_code: None,
            admins: vec![],
            target_timeout_secs: 3600,
            max_chars: 200,
            max_held: 3,
            max_queued_per_contact: 10,
            max_code_attempts: 5,
        }
    }
}

/// Persisted: survives restarts.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct RouterState {
    pub authorized: BTreeSet<Address>,
    pub blocked: BTreeSet<Address>,
}

/// A message from a bridged network.
#[derive(Clone, Debug)]
pub struct Incoming {
    pub from: Address,
    /// `None`: not text (image, voice note, sticker...).
    pub text: Option<String>,
    /// Sent in a group or channel; ignored.
    pub in_group: bool,
    /// Network reference to react to (e.g. signal-cli's timestamp).
    pub msg_ref: String,
    /// Profile name on the network, passed to the field user as a hint.
    pub name: Option<String>,
    pub now: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    Reply {
        to: Address,
        text: String,
    },
    React {
        to: Address,
        msg_ref: String,
        emoji: &'static str,
    },
    /// Hand to `Station::queue_bridged` with this token.
    Queue {
        client: PublicKey,
        from: Address,
        name: Option<String>,
        text: String,
        token: u64,
    },
    /// An admin command: run [`admin::execute`] and reply with its text.
    Admin {
        from: Address,
        command: Command,
    },
}

/// Why a queued message did not reach the field user.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Undelivered {
    /// Queue lifetime (72 h) passed without the user connecting.
    Expired,
    /// The Pharos queue for that user is full.
    QueueFull,
}

#[derive(Clone, Debug)]
struct Held {
    text: String,
    msg_ref: String,
    at: u64,
}

#[derive(Clone, Debug, Default)]
struct Contact {
    target: Option<(PublicKey, u64)>,
    /// Field users this contact exchanged messages with, and when.
    talked: BTreeMap<PublicKey, u64>,
    held: Vec<Held>,
    name: Option<String>,
    offline_note_at: BTreeMap<PublicKey, u64>,
    /// We asked for the access code.
    asked_code: bool,
    wrong_codes: Vec<u64>,
    refused_note_at: Option<u64>,
}

#[derive(Clone, Debug)]
struct Pending {
    to: Address,
    client: PublicKey,
    msg_ref: String,
    text: String,
}

pub struct Router {
    cfg: RouterCfg,
    state: RouterState,
    contacts: BTreeMap<Address, Contact>,
    pending: BTreeMap<u64, Pending>,
    next_token: u64,
}

const HELP: &str = "Escribe @alias y tu mensaje, p. ej. «@ana ¿Llegasteis bien?».";

fn split_at_alias(text: &str) -> Option<(&str, &str)> {
    let rest = text.strip_prefix('@')?;
    let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
    Some((&rest[..end], rest[end..].trim()))
}

fn list(aliases: &[String]) -> String {
    aliases
        .iter()
        .map(|a| format!("@{a}"))
        .collect::<Vec<_>>()
        .join(", ")
}

/// The start of a message, to quote it back.
fn snippet(text: &str) -> String {
    let mut s: String = text.chars().take(40).collect();
    if text.chars().count() > 40 {
        s.push('…');
    }
    format!("«{s}»")
}

impl Router {
    pub fn new(cfg: RouterCfg, state: RouterState) -> Self {
        Self {
            cfg,
            state,
            contacts: BTreeMap::new(),
            pending: BTreeMap::new(),
            next_token: 1,
        }
    }

    pub fn state(&self) -> &RouterState {
        &self.state
    }

    pub fn authorize(&mut self, a: &Address) {
        self.state.blocked.remove(a);
        self.state.authorized.insert(a.clone());
    }

    pub fn block(&mut self, a: &Address) {
        self.state.authorized.remove(a);
        self.state.blocked.insert(a.clone());
    }

    pub fn unblock(&mut self, a: &Address) {
        self.state.blocked.remove(a);
    }

    fn reply(to: &Address, text: impl Into<String>) -> Action {
        Action::Reply {
            to: to.clone(),
            text: text.into(),
        }
    }

    fn authorized(&self, dir: &dyn Directory, from: &Address) -> bool {
        self.cfg.inbound == Inbound::Anyone
            || self.state.authorized.contains(from)
            || self.cfg.admins.contains(from)
            || dir.listed(from)
    }

    fn active(&self, c: &Contact, now: u64) -> Vec<PublicKey> {
        let mut v: Vec<(u64, PublicKey)> = c
            .talked
            .iter()
            .filter(|(_, t)| now.saturating_sub(**t) < self.cfg.target_timeout_secs)
            .map(|(k, t)| (*t, *k))
            .collect();
        v.sort_by(|a, b| b.cmp(a));
        v.into_iter().map(|(_, k)| k).collect()
    }

    fn recent_aliases(&self, dir: &dyn Directory, c: &Contact) -> Vec<String> {
        let mut v: Vec<(u64, PublicKey)> = c.talked.iter().map(|(k, t)| (*t, *k)).collect();
        v.sort_by(|a, b| b.cmp(a));
        v.into_iter()
            .take(5)
            .filter_map(|(_, k)| dir.alias_of(&k))
            .collect()
    }

    fn unknown(&self, dir: &dyn Directory, from: &Address, alias: &str) -> Action {
        let c = self.contacts.get(from).cloned().unwrap_or_default();
        let recent = self.recent_aliases(dir, &c);
        let mut t = format!("No puedo hacer llegar mensajes a @{alias}; no se ha enviado.");
        if !recent.is_empty() {
            t += &format!(" Recientes: {}.", list(&recent));
        }
        Self::reply(from, t)
    }

    fn question(&self, dir: &dyn Directory, from: &Address, several: bool, now: u64) -> String {
        let c = self.contacts.get(from).cloned().unwrap_or_default();
        if several {
            let names: Vec<String> = self
                .active(&c, now)
                .iter()
                .filter_map(|k| dir.alias_of(k))
                .collect();
            format!(
                "Varias personas te escriben por aquí. Empieza cada mensaje con su alias: {}.",
                list(&names)
            )
        } else {
            let recent = self.recent_aliases(dir, &c);
            let mut t = "¿Para quién es? Responde con su alias.".to_owned();
            if !recent.is_empty() {
                t += &format!(" Recientes: {}.", list(&recent));
            }
            t
        }
    }

    /// Keep the message until the contact says who it is for. False if full.
    fn hold(&mut self, m: &Incoming, text: &str) -> bool {
        let max_held = self.cfg.max_held;
        let c = self.contacts.entry(m.from.clone()).or_default();
        if c.held.len() >= max_held {
            return false;
        }
        c.held.push(Held {
            text: text.to_owned(),
            msg_ref: m.msg_ref.clone(),
            at: m.now,
        });
        true
    }

    fn hold_and_ask(
        &mut self,
        dir: &dyn Directory,
        m: &Incoming,
        text: &str,
        several: bool,
    ) -> Vec<Action> {
        if !self.hold(m, text) {
            return vec![Self::reply(
                &m.from,
                format!(
                    "Primero dime para quién es: responde con su alias. No se ha enviado {}.",
                    snippet(text)
                ),
            )];
        }
        vec![Self::reply(
            &m.from,
            self.question(dir, &m.from, several, m.now),
        )]
    }

    fn queue(
        &mut self,
        dir: &dyn Directory,
        from: &Address,
        to: PublicKey,
        text: String,
        msg_ref: String,
        now: u64,
    ) -> Vec<Action> {
        let in_queue = self
            .pending
            .values()
            .filter(|p| p.to == *from && p.client == to)
            .count();
        if in_queue >= self.cfg.max_queued_per_contact {
            let alias = dir.alias_of(&to).unwrap_or_default();
            return vec![Self::reply(
                from,
                format!(
                    "Tienes demasiados mensajes en cola para @{alias}. No se ha enviado {}; espera a que se entreguen.",
                    snippet(&text)
                ),
            )];
        }
        let token = self.next_token;
        self.next_token += 1;
        self.pending.insert(
            token,
            Pending {
                to: from.clone(),
                client: to,
                msg_ref: msg_ref.clone(),
                text: text.clone(),
            },
        );
        let c = self.contacts.entry(from.clone()).or_default();
        c.target = Some((to, now));
        c.talked.insert(to, now);
        let name = c.name.clone();
        let mut out = vec![
            Action::Queue {
                client: to,
                from: from.clone(),
                name,
                text,
                token,
            },
            Action::React {
                to: from.clone(),
                msg_ref,
                emoji: "📡",
            },
        ];
        if !dir.is_listening(&to, now) {
            let last = c.offline_note_at.get(&to).copied();
            if last.is_none_or(|t| now.saturating_sub(t) >= 3600) {
                c.offline_note_at.insert(to, now);
                let alias = dir.alias_of(&to).unwrap_or_default();
                out.push(Self::reply(
                    from,
                    format!(
                        "@{alias} no está a la escucha; se entregará cuando conecte (te aviso con ✅)."
                    ),
                ));
            }
        }
        out
    }

    fn flush_held(
        &mut self,
        dir: &dyn Directory,
        from: &Address,
        to: PublicKey,
        now: u64,
    ) -> Vec<Action> {
        let held = std::mem::take(&mut self.contacts.entry(from.clone()).or_default().held);
        held.into_iter()
            .flat_map(|h| self.queue(dir, from, to, h.text, h.msg_ref, now))
            .collect()
    }

    /// Unauthorised contact on a Pharos with an access code.
    fn code_gate(&mut self, dir: &dyn Directory, m: &Incoming, text: &str) -> Vec<Action> {
        let code = self.cfg.access_code.clone().unwrap_or_default();
        let max = self.cfg.max_code_attempts;
        let now = m.now;
        let c = self.contacts.entry(m.from.clone()).or_default();
        c.wrong_codes.retain(|t| now.saturating_sub(*t) < 3600);
        if c.wrong_codes.len() >= max {
            return vec![];
        }
        if c.asked_code {
            if !code.trim().is_empty() && text == code.trim() {
                c.asked_code = false;
                c.wrong_codes.clear();
                let held = !c.held.is_empty();
                self.state.authorized.insert(m.from.clone());
                let next = if held {
                    self.question(dir, &m.from, false, now)
                } else {
                    HELP.to_owned()
                };
                return vec![Self::reply(
                    &m.from,
                    format!(
                        "Código correcto: ya puedes usar este Pharos. Borra tu mensaje con el código; yo no puedo hacerlo. {next}"
                    ),
                )];
            }
            c.wrong_codes.push(now);
            let t = if c.wrong_codes.len() >= max {
                "Código incorrecto. Demasiados intentos: vuelve a probar dentro de una hora."
            } else {
                "Código incorrecto."
            };
            return vec![Self::reply(&m.from, t)];
        }
        c.asked_code = true;
        let held = self.hold(m, text);
        let mut t =
            "Hola. Para escribir por radio a través de este Pharos, envíame su código de acceso."
                .to_owned();
        if !held {
            t += &format!(" No se ha enviado {}.", snippet(text));
        }
        vec![Self::reply(&m.from, t)]
    }

    /// A message from a bridged contact.
    pub fn on_incoming(&mut self, dir: &dyn Directory, m: Incoming) -> Vec<Action> {
        if m.in_group || self.state.blocked.contains(&m.from) {
            return vec![];
        }
        let Some(text) = m.text.as_deref().map(str::trim) else {
            return vec![Self::reply(
                &m.from,
                "Solo puedo enviar texto por radio; tu mensaje no se ha enviado.",
            )];
        };
        if self.cfg.admins.contains(&m.from)
            && let Some(command) = admin::parse(text, Some(m.from.network()))
        {
            return vec![Action::Admin {
                from: m.from.clone(),
                command,
            }];
        }
        if text.chars().count() > self.cfg.max_chars {
            return vec![Self::reply(
                &m.from,
                format!(
                    "Mensaje demasiado largo: máximo {} caracteres por radio. No se ha enviado.",
                    self.cfg.max_chars
                ),
            )];
        }
        let now = m.now;
        if m.name.is_some() {
            self.contacts.entry(m.from.clone()).or_default().name = m.name.clone();
        }
        if !self.authorized(dir, &m.from) {
            if self.cfg.inbound == Inbound::Code && self.cfg.access_code.is_some() {
                return self.code_gate(dir, &m, text);
            }
            let c = self.contacts.entry(m.from.clone()).or_default();
            if c.refused_note_at
                .is_some_and(|t| now.saturating_sub(t) < 3600)
            {
                return vec![];
            }
            c.refused_note_at = Some(now);
            return vec![Self::reply(
                &m.from,
                "Este Pharos solo acepta mensajes de contactos autorizados. Tu mensaje no se ha enviado.",
            )];
        }
        let has_held = self
            .contacts
            .get(&m.from)
            .is_some_and(|c| !c.held.is_empty());

        // 1. Explicit @alias.
        if let Some((alias, rest)) = split_at_alias(text) {
            let Some(k) = dir.by_alias(alias) else {
                return vec![self.unknown(dir, &m.from, alias)];
            };
            let mut out = self.flush_held(dir, &m.from, k, now);
            if !rest.is_empty() {
                out.extend(self.queue(dir, &m.from, k, rest.to_owned(), m.msg_ref.clone(), now));
            } else if out.is_empty() {
                let c = self.contacts.entry(m.from.clone()).or_default();
                c.target = Some((k, now));
                c.talked.insert(k, now);
                out.push(Self::reply(&m.from, format!("Ahora hablas con @{alias}.")));
            }
            return out;
        }
        // 2. Answer to "¿Para quién es?".
        if has_held
            && !text.contains(char::is_whitespace)
            && let Some(k) = dir.by_alias(text)
        {
            return self.flush_held(dir, &m.from, k, now);
        }
        let c = self.contacts.entry(m.from.clone()).or_default().clone();
        // 3. Several field users at once: @alias is mandatory.
        if self.active(&c, now).len() >= 2 {
            return self.hold_and_ask(dir, &m, text, true);
        }
        // 4. Sticky target.
        if let Some((k, t)) = c.target
            && now.saturating_sub(t) < self.cfg.target_timeout_secs
            && !has_held
        {
            return self.queue(dir, &m.from, k, text.to_owned(), m.msg_ref.clone(), now);
        }
        // 5. Ask.
        let mut out = self.hold_and_ask(dir, &m, text, false);
        if c.talked.is_empty() {
            out.push(Self::reply(&m.from, HELP));
        }
        out
    }

    /// A field user's message for a bridged contact: the text to send there.
    /// Writing first authorises the contact to answer.
    pub fn on_outgoing(
        &mut self,
        from: PublicKey,
        alias: &str,
        to: &Address,
        message: &Message,
        now: u64,
    ) -> Action {
        if !self.state.blocked.contains(to) {
            self.state.authorized.insert(to.clone());
        }
        let c = self.contacts.entry(to.clone()).or_default();
        c.target = Some((from, now));
        c.talked.insert(from, now);
        let text = match message {
            Message::Sos(_) => format!(
                "🆘 SOS de {alias}. {}",
                message.render().trim_start_matches("🆘 SOS").trim()
            ),
            m => format!("{alias}: {}", m.render()),
        };
        Self::reply(to, text.trim_end_matches(". ").to_owned())
    }

    /// The field user's station ACKed the message queued under `token`.
    pub fn on_delivered(&mut self, token: u64) -> Vec<Action> {
        match self.pending.remove(&token) {
            Some(p) => vec![Action::React {
                to: p.to,
                msg_ref: p.msg_ref,
                emoji: "✅",
            }],
            None => vec![],
        }
    }

    /// A queued message will not arrive: tell the sender, quoting it, so they
    /// can resend.
    pub fn on_undelivered(
        &mut self,
        dir: &dyn Directory,
        token: u64,
        why: Undelivered,
    ) -> Vec<Action> {
        let Some(p) = self.pending.remove(&token) else {
            return vec![];
        };
        let alias = dir.alias_of(&p.client).unwrap_or_default();
        let text = match why {
            Undelivered::Expired => format!(
                "No se pudo entregar a @{alias}: lleva 72 h sin conectar. Mensaje: {}. Puedes reenviarlo.",
                snippet(&p.text)
            ),
            Undelivered::QueueFull => format!(
                "@{alias} tiene demasiados mensajes pendientes. No se ha enviado {}.",
                snippet(&p.text)
            ),
        };
        vec![
            Action::React {
                to: p.to.clone(),
                msg_ref: p.msg_ref,
                emoji: "❌",
            },
            Self::reply(&p.to, text),
        ]
    }

    /// Drop held messages nobody claimed within the target timeout.
    pub fn on_tick(&mut self, now: u64) -> Vec<Action> {
        let mut out = Vec::new();
        for (addr, c) in &mut self.contacts {
            let (keep, drop): (Vec<Held>, Vec<Held>) = c
                .held
                .drain(..)
                .partition(|h| now.saturating_sub(h.at) < self.cfg.target_timeout_secs);
            c.held = keep;
            for h in drop {
                out.push(Self::reply(
                    addr,
                    format!(
                        "No me dijiste para quién era {}; no se ha enviado.",
                        snippet(&h.text)
                    ),
                ));
            }
        }
        out
    }

    /// Text for the admins about a pending registration.
    pub fn approval_request(&self, alias: &str, key: &PublicKey) -> Vec<Action> {
        self.cfg
            .admins
            .iter()
            .map(|a| {
                Self::reply(
                    a,
                    format!(
                        "¿Aceptar a @{alias} ({})? Responde «aceptar {alias}» o «rechazar {alias}».",
                        key.safety_number()
                    ),
                )
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Dir(Vec<(PublicKey, &'static str, bool)>, Vec<Address>);

    impl Directory for Dir {
        fn by_alias(&self, alias: &str) -> Option<PublicKey> {
            self.0
                .iter()
                .find(|(_, a, _)| a.eq_ignore_ascii_case(alias))
                .map(|(k, _, _)| *k)
        }
        fn alias_of(&self, key: &PublicKey) -> Option<String> {
            self.0
                .iter()
                .find(|(k, _, _)| k == key)
                .map(|(_, a, _)| a.to_string())
        }
        fn listed(&self, from: &Address) -> bool {
            self.1.contains(from)
        }
        fn is_listening(&self, key: &PublicKey, _now: u64) -> bool {
            self.0.iter().any(|(k, _, l)| k == key && *l)
        }
    }

    const ANA: PublicKey = PublicKey([1; 32]);
    const BEA: PublicKey = PublicKey([2; 32]);
    const T: u64 = 1_800_000_000;

    fn mama() -> Address {
        Address::SignalPhone("34611111111".into())
    }

    fn dir() -> Dir {
        Dir(vec![(ANA, "ana", true), (BEA, "bea", false)], vec![mama()])
    }

    fn router() -> Router {
        Router::new(RouterCfg::default(), RouterState::default())
    }

    fn with(cfg: RouterCfg) -> Router {
        Router::new(cfg, RouterState::default())
    }

    fn msg(text: &str, now: u64) -> Incoming {
        Incoming {
            from: mama(),
            text: Some(text.into()),
            in_group: false,
            msg_ref: format!("r{now}"),
            name: Some("Mamá".into()),
            now,
        }
    }

    fn queued(a: &[Action]) -> Vec<(PublicKey, String)> {
        a.iter()
            .filter_map(|x| match x {
                Action::Queue { client, text, .. } => Some((*client, text.clone())),
                _ => None,
            })
            .collect()
    }

    fn replies(a: &[Action]) -> String {
        a.iter()
            .filter_map(|x| match x {
                Action::Reply { text, .. } => Some(text.as_str()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join(" | ")
    }

    #[test]
    fn first_message_is_held_until_the_alias_arrives() {
        let (mut r, d) = (router(), dir());
        let a = r.on_incoming(&d, msg("¿Llegasteis bien?", T));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("¿Para quién es?"));
        let a = r.on_incoming(&d, msg("Ana", T + 10));
        assert_eq!(queued(&a), vec![(ANA, "¿Llegasteis bien?".into())]);
        let a = r.on_incoming(&d, msg("Besos", T + 20));
        assert_eq!(queued(&a), vec![(ANA, "Besos".into())]);
        let a = r.on_incoming(&d, msg("¿Hola?", T + 20 + 3600));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("Recientes: @ana"));
    }

    #[test]
    fn at_alias_switches_and_unknown_is_refused() {
        let (mut r, d) = (router(), dir());
        let a = r.on_incoming(&d, msg("@bea ¿Qué tal el valle?", T));
        assert_eq!(queued(&a), vec![(BEA, "¿Qué tal el valle?".into())]);
        assert!(replies(&a).contains("@bea no está a la escucha"));
        let a = r.on_incoming(&d, msg("@pepe hola", T + 1));
        assert!(queued(&a).is_empty());
        assert!(
            replies(&a).starts_with("No puedo hacer llegar mensajes a @pepe; no se ha enviado.")
        );
    }

    #[test]
    fn two_active_field_users_require_at_alias() {
        let (mut r, d) = (router(), dir());
        r.on_outgoing(ANA, "ana", &mama(), &Message::Text("Hola mamá".into()), T);
        r.on_outgoing(BEA, "bea", &mama(), &Message::Canned(0), T + 5);
        let a = r.on_incoming(&d, msg("¿Estáis juntas?", T + 60));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("@bea, @ana"));
        let a = r.on_incoming(&d, msg("@ana sí", T + 70));
        assert_eq!(
            queued(&a),
            vec![(ANA, "¿Estáis juntas?".into()), (ANA, "sí".into())]
        );
    }

    #[test]
    fn access_code_authorises_and_keeps_the_first_message() {
        let d = Dir(vec![(ANA, "ana", true)], vec![]);
        let mut r = with(RouterCfg {
            access_code: Some("caballo pila grapa".into()),
            ..RouterCfg::default()
        });
        let a = r.on_incoming(&d, msg("¿todo bien?", T));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("código de acceso"));
        assert_eq!(
            replies(&r.on_incoming(&d, msg("nope", T + 1))),
            "Código incorrecto."
        );
        let a = r.on_incoming(&d, msg("caballo pila grapa", T + 2));
        assert!(replies(&a).contains("Borra tu mensaje"));
        assert!(replies(&a).contains("¿Para quién es?"));
        assert!(r.state().authorized.contains(&mama()));
        assert_eq!(
            queued(&r.on_incoming(&d, msg("ana", T + 3))),
            vec![(ANA, "¿todo bien?".into())]
        );
    }

    #[test]
    fn too_many_wrong_codes_silence_the_bot_for_an_hour() {
        let d = Dir(vec![(ANA, "ana", true)], vec![]);
        let mut r = with(RouterCfg {
            access_code: Some("secreto largo".into()),
            ..RouterCfg::default()
        });
        r.on_incoming(&d, msg("hola", T));
        for i in 1..=5 {
            r.on_incoming(&d, msg("mal", T + i));
        }
        assert!(r.on_incoming(&d, msg("secreto largo", T + 10)).is_empty());
        assert!(!r.on_incoming(&d, msg("secreto largo", T + 3700)).is_empty());
    }

    #[test]
    fn contacts_mode_and_writing_first() {
        let d = Dir(vec![(ANA, "ana", true)], vec![]);
        let mut r = with(RouterCfg {
            inbound: Inbound::Contacts,
            ..RouterCfg::default()
        });
        assert!(replies(&r.on_incoming(&d, msg("@ana hola", T))).contains("no se ha enviado"));
        assert!(
            r.on_incoming(&d, msg("@ana hola", T + 1)).is_empty(),
            "one refusal note per hour"
        );
        r.on_outgoing(ANA, "ana", &mama(), &Message::Text("Hola".into()), T + 2);
        assert_eq!(queued(&r.on_incoming(&d, msg("Hola hija", T + 3))).len(), 1);
    }

    #[test]
    fn groups_blocked_media_long_text_and_feedback() {
        let (mut r, d) = (router(), dir());
        let mut g = msg("@ana hola", T);
        g.in_group = true;
        assert!(r.on_incoming(&d, g).is_empty());
        let mut media = msg("", T);
        media.text = None;
        assert!(replies(&r.on_incoming(&d, media)).contains("no se ha enviado"));
        assert!(replies(&r.on_incoming(&d, msg(&"a".repeat(201), T))).contains("máximo 200"));
        let a = r.on_incoming(&d, msg("@ana hola", T));
        let Some(Action::Queue { token, .. }) = a.first() else {
            panic!("{a:?}")
        };
        let token = *token;
        assert!(a.contains(&Action::React {
            to: mama(),
            msg_ref: format!("r{T}"),
            emoji: "📡"
        }));
        let a = r.on_incoming(&d, msg("@ana ¿me oyes?", T + 5));
        let Some(Action::Queue { token: t2, .. }) = a.first() else {
            panic!("{a:?}")
        };
        let t2 = *t2;
        assert_eq!(
            r.on_delivered(token),
            vec![Action::React {
                to: mama(),
                msg_ref: format!("r{T}"),
                emoji: "✅"
            }]
        );
        let a = r.on_undelivered(&d, t2, Undelivered::Expired);
        assert!(replies(&a).contains("«¿me oyes?»"), "{a:?}");
        r.block(&mama());
        assert!(r.on_incoming(&d, msg("@ana hola", T + 9)).is_empty());
    }

    #[test]
    fn admin_commands_are_routed() {
        let admin = Address::SignalPhone("34600000000".into());
        let mut r = with(RouterCfg {
            admins: vec![admin.clone()],
            ..RouterCfg::default()
        });
        let mut m = msg("aceptar @bea", T);
        m.from = admin.clone();
        assert_eq!(
            r.on_incoming(&dir(), m),
            vec![Action::Admin {
                from: admin.clone(),
                command: Command::Accept("bea".into())
            }]
        );
        // Ordinary chat from an admin is routed like anyone else's.
        let mut m = msg("hola", T + 1);
        m.from = admin;
        assert!(replies(&r.on_incoming(&dir(), m)).contains("¿Para quién es?"));
    }

    #[test]
    fn outgoing_text_and_sos_formatting() {
        let mut r = router();
        let a = r.on_outgoing(
            ANA,
            "ana",
            &mama(),
            &Message::Text("Hemos llegado".into()),
            T,
        );
        assert_eq!(a, Router::reply(&mama(), "ana: Hemos llegado"));
        let p = keryx::position::Position {
            lat: 42.6,
            lon: 0.6,
        };
        let Action::Reply { text, .. } =
            r.on_outgoing(ANA, "ana", &mama(), &Message::Sos(Some(p)), T)
        else {
            panic!()
        };
        assert!(
            text.starts_with("🆘 SOS de ana. https://maps.google.com/?q=42.60000,0.60000"),
            "{text}"
        );
    }
}
