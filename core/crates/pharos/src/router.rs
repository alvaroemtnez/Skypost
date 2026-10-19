//! The bridge bot: routes messages from Signal/WhatsApp/MeshCore contacts to
//! field users by alias, and formats field messages for them (see
//! `docs/spec/pharos.md` §2). Pure logic: the shell feeds events in and
//! carries out the returned actions. Bot texts are Spanish.

use keryx::bridge::Address;
use keryx::{Message, PublicKey};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// What the router needs to know about field users.
pub trait Directory {
    /// Registered field user with this alias (case-insensitive, no `@`).
    fn by_alias(&self, alias: &str) -> Option<PublicKey>;
    fn alias_of(&self, key: &PublicKey) -> Option<String>;
    /// The field user lists this contact as allowed to write first.
    fn allows(&self, key: &PublicKey, from: &Address) -> bool;
    fn is_listening(&self, key: &PublicKey, now: u64) -> bool;
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Inbound {
    /// Allowed contacts, or contacts the field user wrote to first.
    #[default]
    Contacts,
    Anyone,
}

#[derive(Clone, Debug)]
pub struct RouterCfg {
    pub inbound: Inbound,
    pub target_timeout_secs: u64,
    pub max_chars: usize,
    pub max_held: usize,
    pub max_queued_per_contact: usize,
    /// Operator account: approves registrations ("aceptar alias").
    pub admin: Option<Address>,
}

impl Default for RouterCfg {
    fn default() -> Self {
        Self {
            inbound: Inbound::Contacts,
            target_timeout_secs: 3600,
            max_chars: 200,
            max_held: 3,
            max_queued_per_contact: 10,
            admin: None,
        }
    }
}

/// A message from a bridged network.
#[derive(Clone, Debug)]
pub struct Incoming {
    pub from: Address,
    /// `None`: not text (image, voice note, sticker...).
    pub text: Option<String>,
    /// Sent in a group chat; ignored.
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
    Approve {
        alias: String,
    },
    Refuse {
        alias: String,
    },
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
}

pub struct Router {
    cfg: RouterCfg,
    contacts: BTreeMap<Address, Contact>,
    /// (field user, contact) pairs where the field user wrote first.
    wrote_first: BTreeSet<(PublicKey, Address)>,
    /// token → (contact, field user, message ref).
    pending: BTreeMap<u64, (Address, PublicKey, String)>,
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

impl Router {
    pub fn new(cfg: RouterCfg) -> Self {
        Self {
            cfg,
            contacts: BTreeMap::new(),
            wrote_first: BTreeSet::new(),
            pending: BTreeMap::new(),
            next_token: 1,
        }
    }

    fn reply(to: &Address, text: impl Into<String>) -> Action {
        Action::Reply {
            to: to.clone(),
            text: text.into(),
        }
    }

    /// Registered and allowed for this contact.
    fn resolve(&self, dir: &dyn Directory, alias: &str, from: &Address) -> Option<PublicKey> {
        let k = dir.by_alias(alias)?;
        let ok = self.cfg.inbound == Inbound::Anyone
            || dir.allows(&k, from)
            || self.wrote_first.contains(&(k, from.clone()));
        ok.then_some(k)
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
        let mut t = format!("No puedo hacer llegar mensajes a @{alias}.");
        if !recent.is_empty() {
            t += &format!(" Recientes: {}.", list(&recent));
        }
        Self::reply(from, t)
    }

    fn hold_and_ask(
        &mut self,
        dir: &dyn Directory,
        m: &Incoming,
        text: &str,
        several: bool,
    ) -> Vec<Action> {
        let max_held = self.cfg.max_held;
        let c = self.contacts.entry(m.from.clone()).or_default();
        if c.held.len() >= max_held {
            return vec![Self::reply(
                &m.from,
                "Primero dime para quién es: responde con su alias.",
            )];
        }
        c.held.push(Held {
            text: text.to_owned(),
            msg_ref: m.msg_ref.clone(),
            at: m.now,
        });
        let c = self.contacts[&m.from].clone();
        let text = if several {
            let names: Vec<String> = self
                .active(&c, m.now)
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
        };
        vec![Self::reply(&m.from, text)]
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
            .filter(|(a, k, _)| a == from && *k == to)
            .count();
        if in_queue >= self.cfg.max_queued_per_contact {
            return vec![Self::reply(
                from,
                "Hay demasiados mensajes en cola. Espera a que se entreguen.",
            )];
        }
        let token = self.next_token;
        self.next_token += 1;
        self.pending
            .insert(token, (from.clone(), to, msg_ref.clone()));
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
                    format!("@{alias} no está a la escucha; se entregará cuando conecte."),
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

    fn admin(&self, m: &Incoming, text: &str) -> Option<Vec<Action>> {
        if self.cfg.admin.as_ref() != Some(&m.from) {
            return None;
        }
        let mut words = text.split_whitespace();
        let verb = words.next()?.to_lowercase();
        let alias = words.next()?.trim_start_matches('@').to_owned();
        match verb.as_str() {
            "aceptar" => Some(vec![Action::Approve { alias }]),
            "rechazar" => Some(vec![Action::Refuse { alias }]),
            _ => None,
        }
    }

    /// A message from a bridged contact.
    pub fn on_incoming(&mut self, dir: &dyn Directory, m: Incoming) -> Vec<Action> {
        if m.in_group {
            return vec![];
        }
        let Some(text) = m.text.as_deref().map(str::trim) else {
            return vec![Self::reply(&m.from, "Solo puedo enviar texto.")];
        };
        if let Some(a) = self.admin(&m, text) {
            return a;
        }
        if text.chars().count() > self.cfg.max_chars {
            return vec![Self::reply(
                &m.from,
                format!(
                    "Mensaje demasiado largo: máximo {} caracteres por radio.",
                    self.cfg.max_chars
                ),
            )];
        }
        let now = m.now;
        let c = self.contacts.entry(m.from.clone()).or_default();
        if m.name.is_some() {
            c.name = m.name.clone();
        }
        let has_held = !c.held.is_empty();

        // 1. Explicit @alias.
        if let Some((alias, rest)) = split_at_alias(text) {
            let Some(k) = self.resolve(dir, alias, &m.from) else {
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
            && let Some(k) = self.resolve(dir, text, &m.from)
        {
            return self.flush_held(dir, &m.from, k, now);
        }
        let c = self.contacts[&m.from].clone();
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
    pub fn on_outgoing(
        &mut self,
        from: PublicKey,
        alias: &str,
        to: &Address,
        message: &Message,
        now: u64,
    ) -> Action {
        self.wrote_first.insert((from, to.clone()));
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
            Some((to, _, msg_ref)) => vec![Action::React {
                to,
                msg_ref,
                emoji: "✅",
            }],
            None => vec![],
        }
    }

    /// Station gave up on a queued message (queue lifetime) or refused it.
    pub fn on_undelivered(&mut self, dir: &dyn Directory, token: u64) -> Vec<Action> {
        match self.pending.remove(&token) {
            Some((to, k, _)) => {
                let alias = dir.alias_of(&k).unwrap_or_default();
                vec![Self::reply(
                    &to,
                    format!("No se pudo entregar un mensaje a @{alias}."),
                )]
            }
            None => vec![],
        }
    }

    /// Drop held messages nobody claimed within the target timeout.
    pub fn on_tick(&mut self, now: u64) -> Vec<Action> {
        let mut out = Vec::new();
        for (addr, c) in &mut self.contacts {
            let before = c.held.len();
            c.held
                .retain(|h| now.saturating_sub(h.at) < self.cfg.target_timeout_secs);
            if c.held.len() < before {
                out.push(Self::reply(
                    addr,
                    "No me dijiste para quién era; descarto el mensaje.",
                ));
            }
        }
        out
    }

    /// Text for the operator about a pending registration.
    pub fn approval_request(&self, alias: &str, key: &PublicKey) -> Option<Action> {
        let admin = self.cfg.admin.as_ref()?;
        Some(Self::reply(
            admin,
            format!(
                "¿Aceptar a @{alias} ({})? Responde «aceptar {alias}» o «rechazar {alias}».",
                key.safety_number()
            ),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Dir(
        Vec<(PublicKey, &'static str, bool)>,
        Vec<(PublicKey, Address)>,
    );

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
        fn allows(&self, key: &PublicKey, from: &Address) -> bool {
            self.1.iter().any(|(k, a)| k == key && a == from)
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
        Dir(
            vec![(ANA, "ana", true), (BEA, "bea", false)],
            vec![(ANA, mama()), (BEA, mama())],
        )
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
        let (mut r, d) = (Router::new(RouterCfg::default()), dir());
        let a = r.on_incoming(&d, msg("¿Llegasteis bien?", T));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("¿Para quién es?"));
        let a = r.on_incoming(&d, msg("Ana", T + 10));
        assert_eq!(queued(&a), vec![(ANA, "¿Llegasteis bien?".into())]);
        // Sticky target for the next hour.
        let a = r.on_incoming(&d, msg("Besos", T + 20));
        assert_eq!(queued(&a), vec![(ANA, "Besos".into())]);
        let a = r.on_incoming(&d, msg("¿Hola?", T + 20 + 3600));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).contains("Recientes: @ana"));
    }

    #[test]
    fn at_alias_switches_and_unknown_is_refused() {
        let (mut r, d) = (Router::new(RouterCfg::default()), dir());
        let a = r.on_incoming(&d, msg("@bea ¿Qué tal el valle?", T));
        assert_eq!(queued(&a), vec![(BEA, "¿Qué tal el valle?".into())]);
        assert!(replies(&a).contains("@bea no está a la escucha"));
        let a = r.on_incoming(&d, msg("@pepe hola", T + 1));
        assert!(queued(&a).is_empty());
        assert!(replies(&a).starts_with("No puedo hacer llegar mensajes a @pepe."));
    }

    #[test]
    fn two_active_field_users_require_at_alias() {
        let (mut r, d) = (Router::new(RouterCfg::default()), dir());
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
    fn strangers_need_permission_unless_inbound_is_anyone() {
        let d = Dir(vec![(ANA, "ana", true)], vec![]);
        let mut r = Router::new(RouterCfg::default());
        assert!(queued(&r.on_incoming(&d, msg("@ana hola", T))).is_empty());
        // Once Ana writes first, the contact may answer.
        r.on_outgoing(ANA, "ana", &mama(), &Message::Text("Hola".into()), T + 1);
        assert_eq!(queued(&r.on_incoming(&d, msg("Hola hija", T + 2))).len(), 1);
        let mut open = Router::new(RouterCfg {
            inbound: Inbound::Anyone,
            ..RouterCfg::default()
        });
        assert_eq!(queued(&open.on_incoming(&d, msg("@ana hola", T))).len(), 1);
    }

    #[test]
    fn groups_media_long_text_and_feedback() {
        let (mut r, d) = (Router::new(RouterCfg::default()), dir());
        let mut g = msg("@ana hola", T);
        g.in_group = true;
        assert!(r.on_incoming(&d, g).is_empty());
        let mut media = msg("", T);
        media.text = None;
        assert_eq!(
            replies(&r.on_incoming(&d, media)),
            "Solo puedo enviar texto."
        );
        assert!(replies(&r.on_incoming(&d, msg(&"a".repeat(201), T))).contains("máximo 200"));
        let a = r.on_incoming(&d, msg("@ana hola", T));
        let Some(Action::Queue { token, .. }) = a.first() else {
            panic!("{a:?}")
        };
        assert!(a.contains(&Action::React {
            to: mama(),
            msg_ref: format!("r{T}"),
            emoji: "📡"
        }));
        assert_eq!(
            r.on_delivered(*token),
            vec![Action::React {
                to: mama(),
                msg_ref: format!("r{T}"),
                emoji: "✅"
            }]
        );
    }

    #[test]
    fn outgoing_text_and_sos_formatting() {
        let mut r = Router::new(RouterCfg::default());
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

    #[test]
    fn admin_approves_by_alias() {
        let admin = Address::SignalPhone("34600000000".into());
        let mut r = Router::new(RouterCfg {
            admin: Some(admin.clone()),
            ..RouterCfg::default()
        });
        let mut m = msg("aceptar @bea", T);
        m.from = admin;
        assert_eq!(
            r.on_incoming(&dir(), m),
            vec![Action::Approve {
                alias: "bea".into()
            }]
        );
    }
}
