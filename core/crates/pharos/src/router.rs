//! The bridge bot: routes messages from Signal/WhatsApp/MeshCore contacts to
//! field users by alias, and formats field messages for them (see
//! `docs/spec/pharos.md` §2). Pure logic: the shell feeds events in and
//! carries out the returned actions. Bot texts are Spanish.
//!
//! One-to-one chats only. Every refusal is answered, so nobody believes an
//! undelivered message went out. On Signal and WhatsApp the bot explains
//! itself (welcome, `/ayuda`); on MeshCore, made for short messages, it keeps
//! every reply short and sends no welcome.

use crate::admin::{self, Text};
use keryx::admin::{Command, Switch, Switches};
use keryx::bridge::Address;
use keryx::{Message, PublicKey};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// What the router needs to know from the station: field users, the access
/// switches, admins and requests.
pub trait Directory {
    /// Registered field user with this alias (case-insensitive, no `@`).
    fn by_alias(&self, alias: &str) -> Option<PublicKey>;
    fn alias_of(&self, key: &PublicKey) -> Option<String>;
    fn is_listening(&self, key: &PublicKey, now: u64) -> bool;
    fn switches(&self) -> Switches;
    fn is_admin(&self, a: &Address) -> bool;
    /// This contact has an access request pending.
    fn requested(&self, a: &Address) -> bool;
    /// The admin password exists and the `login` switch is on.
    fn login_available(&self) -> bool;
}

#[derive(Clone, Debug)]
pub struct RouterCfg {
    /// The Pharos access code: a contact who sends it is admitted at once.
    pub access_code: Option<String>,
    /// How the welcome names this station, e.g. "casa (EA5YYY)".
    pub station: String,
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
            access_code: None,
            station: String::new(),
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
    /// Their access request was refused: ignored until an admin allows them.
    #[serde(default)]
    pub refused: BTreeSet<Address>,
    /// Got the welcome already (Signal and WhatsApp).
    #[serde(default)]
    pub welcomed: BTreeSet<Address>,
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
    /// An admin's command: run it with [`admin::Admin::run`].
    Admin {
        from: Address,
        text: Text,
    },
    /// The admin password from a bridge account; if it is right, `then` runs.
    AdminLogin {
        from: Address,
        password: String,
        then: Option<Text>,
    },
    /// An unknown contact asks for access: `Station::request_contact`.
    RequestAccess {
        from: Address,
        name: Option<String>,
    },
    /// The contact got in with the access code: drop its pending request.
    Withdraw {
        from: Address,
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
    /// `/admin` from someone who is not an admin: the command waits for the
    /// password, sent at this time.
    admin_asked: Option<(Text, u64)>,
    /// Last "your request is still pending" note.
    request_note_at: Option<u64>,
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

/// The one-line help (MeshCore, and after a correct access code there).
const HELP: &str = "Escribe @alias y tu mensaje, p. ej. «@ana ¿Llegasteis bien?».";

/// How long a `/admin` command waits for the password.
const PASSWORD_WAIT_SECS: u64 = 600;

/// The language of the user help: the bot's other texts are Spanish, admin
/// texts English, and the help answers in the language it was asked in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Lang {
    Es,
    En,
    /// Valencian.
    Ca,
}

/// `/ayuda`, `/help` or `/ajuda`: the user help, in that language.
fn user_help_command(text: &str) -> Option<Lang> {
    [
        ("/ayuda", Lang::Es),
        ("/help", Lang::En),
        ("/ajuda", Lang::Ca),
    ]
    .into_iter()
    .find(|(h, _)| text.trim().eq_ignore_ascii_case(h))
    .map(|(_, l)| l)
}

/// The one-line help, for MeshCore.
fn short_help(lang: Lang) -> &'static str {
    match lang {
        Lang::Es => HELP,
        Lang::En => "Write @alias and your message, e.g. «@ana Did you get there OK?».",
        Lang::Ca => "Escriu @àlies i el teu missatge, p. ex. «@ana Heu arribat bé?».",
    }
}

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
        self.state.refused.remove(a);
        self.state.authorized.insert(a.clone());
    }

    /// Contacts the bot knows of (to resolve MeshCore key prefixes).
    pub fn known_contacts(&self) -> impl Iterator<Item = Address> + '_ {
        self.state
            .authorized
            .iter()
            .chain(&self.state.blocked)
            .chain(self.contacts.keys())
            .cloned()
    }

    /// The contact's profile name on its network, if it ever sent one.
    pub fn profile_name(&self, a: &Address) -> Option<String> {
        self.contacts.get(a).and_then(|c| c.name.clone())
    }

    /// An admin decided the contact's access request.
    pub fn on_request_decided(&mut self, a: &Address, accept: bool) -> Vec<Action> {
        if !accept {
            self.state.refused.insert(a.clone());
            let dropped = self
                .contacts
                .get_mut(a)
                .map(|c| std::mem::take(&mut c.held).len())
                .unwrap_or(0);
            let mut t = "Tu solicitud no ha sido aceptada.".to_owned();
            if dropped > 0 {
                t += " Tus mensajes no se han enviado.";
            }
            return vec![Self::reply(a, t)];
        }
        self.authorize(a);
        let mut out = vec![Self::say(
            a,
            "Tu solicitud ha sido aceptada: ya puedes usar este Pharos.",
            "Solicitud aceptada.",
        )];
        let welcome = self.welcome(a);
        let welcomed = welcome.is_some();
        out.extend(welcome);
        if self.contacts.get(a).is_some_and(|c| !c.held.is_empty()) {
            out.push(Self::reply(
                a,
                "¿Para quién es lo que me enviaste? Responde con su alias.",
            ));
        } else if !welcomed {
            out.push(Self::reply(a, HELP));
        }
        out
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

    /// MeshCore is made for short messages: no welcome, short replies.
    pub fn terse(a: &Address) -> bool {
        matches!(a, Address::MeshcoreContact(_))
    }

    /// The long text on Signal and WhatsApp, the short one on MeshCore.
    fn say(to: &Address, long: impl Into<String>, short: impl Into<String>) -> Action {
        Self::reply(
            to,
            if Self::terse(to) {
                short.into()
            } else {
                long.into()
            },
        )
    }

    fn user_help(&self, lang: Lang) -> String {
        let max = self.cfg.max_chars;
        match lang {
            Lang::Es => format!(
                "• Para escribir a alguien: @alias y el mensaje, p. ej. «@ana ¿Llegasteis bien?». Después basta con escribir: va a la misma persona.
• Solo texto, hasta {max} caracteres. Llega cuando su radio conecta: puede tardar.
• 📡 en cola · ✅ entregado · ❌ no llegará (te digo por qué).
• /ayuda muestra esto otra vez (/help in English, /ajuda en valencià)."
            ),
            Lang::En => format!(
                "• To write to someone: @alias and the message, e.g. «@ana Did you get there OK?». After that just write: it goes to the same person.
• Text only, up to {max} characters. It arrives when their radio connects: it can take a while.
• 📡 queued · ✅ delivered · ❌ will not arrive (I say why).
• /help shows this again. Other replies from this Pharos are in Spanish."
            ),
            Lang::Ca => format!(
                "• Per a escriure a algú: @àlies i el missatge, p. ex. «@ana Heu arribat bé?». Després només cal escriure: va a la mateixa persona.
• Només text, fins a {max} caràcters. Arriba quan la seua ràdio connecta: pot tardar.
• 📡 en cua · ✅ lliurat · ❌ no arribarà (et dic per què).
• /ajuda mostra açò una altra vegada."
            ),
        }
    }

    /// Once per contact, on Signal and WhatsApp: who the bot is and how to use it.
    fn welcome(&mut self, to: &Address) -> Option<Action> {
        if Self::terse(to) || !self.state.welcomed.insert(to.clone()) {
            return None;
        }
        let me = if self.cfg.station.is_empty() {
            "un Pharos".to_owned()
        } else {
            format!("el Pharos {}", self.cfg.station)
        };
        Some(Self::reply(
            to,
            format!(
                "👋 Hola, soy {me}: una estación de radio que lleva mensajes a quienes están sin cobertura.\n{}",
                self.user_help(Lang::Es)
            ),
        ))
    }

    /// `/ayuda` from an authorised contact.
    /// Counts as the welcome: a contact who asks in English gets no Spanish one.
    fn help_reply(&mut self, dir: &dyn Directory, to: &Address, lang: Lang) -> Action {
        self.state.welcomed.insert(to.clone());
        let c = self.contacts.get(to).cloned().unwrap_or_default();
        let recent = self.recent_aliases(dir, &c);
        let mut long = self.user_help(lang);
        let mut short = short_help(lang).to_owned();
        if !recent.is_empty() {
            let word = match lang {
                Lang::Es => "Recientes",
                Lang::En => "Recent",
                Lang::Ca => "Recents",
            };
            long += &format!("\n{word}: {}.", list(&recent));
            short += &format!(" {word}: {}.", list(&recent));
        }
        Self::say(to, long, short)
    }

    fn authorized(&self, dir: &dyn Directory, from: &Address) -> bool {
        dir.switches().get(Switch::Open)
            || self.state.authorized.contains(from)
            || dir.is_admin(from)
    }

    /// `/admin …`: run it for an admin; otherwise ask for the password, if
    /// password login is available.
    fn admin_text(&mut self, dir: &dyn Directory, m: &Incoming, body: &str) -> Vec<Action> {
        let text = admin::parse(body, Some(m.from.network()));
        if dir.is_admin(&m.from) {
            return vec![Action::Admin {
                from: m.from.clone(),
                text,
            }];
        }
        if let Text::Command(Command::Login(password)) = text {
            return vec![Action::AdminLogin {
                from: m.from.clone(),
                password,
                then: None,
            }];
        }
        if !dir.login_available() {
            return vec![Self::reply(&m.from, "You are not an admin of this Pharos.")];
        }
        self.contacts.entry(m.from.clone()).or_default().admin_asked = Some((text, m.now));
        vec![Self::say(
            &m.from,
            "To manage this Pharos, send me its admin password.",
            "Send the admin password.",
        )]
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
            if Self::terse(from) {
                format!("Empieza con el alias: {}.", list(&names))
            } else {
                format!(
                    "Varias personas te escriben por aquí. Empieza cada mensaje con su alias: {}.",
                    list(&names)
                )
            }
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
                out.push(Self::say(
                    from,
                    format!(
                        "@{alias} no está a la escucha; se entregará cuando conecte (te aviso con ✅)."
                    ),
                    format!("@{alias} no escucha; se entregará al conectar."),
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

    /// A contact that may not write yet (closed Pharos). The access code
    /// admits at once; otherwise, with requests on, its first message becomes
    /// an access request and is held; with them off, it is asked for the code
    /// if there is one, or told it may not write.
    fn gate(&mut self, dir: &dyn Directory, m: &Incoming, text: &str) -> Vec<Action> {
        if self.state.refused.contains(&m.from) {
            return vec![];
        }
        let now = m.now;
        let max = self.cfg.max_code_attempts;
        let code = self
            .cfg
            .access_code
            .clone()
            .filter(|c| !c.trim().is_empty());
        let c = self.contacts.entry(m.from.clone()).or_default();
        c.wrong_codes.retain(|t| now.saturating_sub(*t) < 3600);
        if c.wrong_codes.len() >= max {
            return vec![];
        }
        if code.as_deref().is_some_and(|k| text == k.trim()) {
            c.asked_code = false;
            c.wrong_codes.clear();
            let held = !c.held.is_empty();
            self.authorize(&m.from);
            let mut out = vec![
                Action::Withdraw {
                    from: m.from.clone(),
                },
                Self::say(
                    &m.from,
                    "Código correcto: ya puedes usar este Pharos.\n⚠️ BORRA TU MENSAJE CON EL CÓDIGO. YO NO PUEDO HACERLO.",
                    "Código correcto. ⚠️ BORRA TU MENSAJE CON EL CÓDIGO.",
                ),
            ];
            let welcome = self.welcome(&m.from);
            let welcomed = welcome.is_some();
            out.extend(welcome);
            if held {
                out.push(Self::reply(
                    &m.from,
                    self.question(dir, &m.from, false, now),
                ));
            } else if !welcomed {
                out.push(Self::reply(&m.from, HELP));
            }
            return out;
        }
        let help = user_help_command(text).is_some();
        if dir.switches().get(Switch::Requests) {
            let held = help || self.hold(m, text);
            if !dir.requested(&m.from) {
                let name = self.contacts.get(&m.from).and_then(|c| c.name.clone());
                return vec![Action::RequestAccess {
                    from: m.from.clone(),
                    name,
                }];
            }
            let c = self.contacts.entry(m.from.clone()).or_default();
            if !held {
                return vec![Self::reply(
                    &m.from,
                    format!(
                        "Tu solicitud sigue pendiente y ya guardo bastantes mensajes. No se ha enviado {}.",
                        snippet(text)
                    ),
                )];
            }
            if c.request_note_at
                .is_some_and(|t| now.saturating_sub(t) < 3600)
            {
                return vec![];
            }
            c.request_note_at = Some(now);
            return vec![Self::say(
                &m.from,
                "Tu solicitud sigue pendiente. Guardo tu mensaje y lo entrego si la aceptan.",
                "Solicitud pendiente.",
            )];
        }
        if code.is_some() {
            if c.asked_code {
                c.wrong_codes.push(now);
                let t = if c.wrong_codes.len() >= max {
                    "Código incorrecto. Demasiados intentos: vuelve a probar dentro de una hora."
                } else {
                    "Código incorrecto."
                };
                return vec![Self::reply(&m.from, t)];
            }
            c.asked_code = true;
            let held = help || self.hold(m, text);
            let mut long =
                "Hola. Para escribir por radio a través de este Pharos, envíame su código de acceso."
                    .to_owned();
            let mut short = "Envía el código de acceso de este Pharos.".to_owned();
            if !held {
                let not_sent = format!(" No se ha enviado {}.", snippet(text));
                long += &not_sent;
                short += &not_sent;
            }
            return vec![Self::say(&m.from, long, short)];
        }
        if c.refused_note_at
            .is_some_and(|t| now.saturating_sub(t) < 3600)
        {
            return vec![];
        }
        c.refused_note_at = Some(now);
        vec![Self::say(
            &m.from,
            "Este Pharos solo acepta mensajes de contactos autorizados. Tu mensaje no se ha enviado.",
            "Solo contactos autorizados. No se ha enviado.",
        )]
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
        // The password after `/admin`: checked first, never routed.
        if let Some(c) = self.contacts.get_mut(&m.from)
            && let Some((then, at)) = c.admin_asked.take()
            && m.now.saturating_sub(at) < PASSWORD_WAIT_SECS
        {
            return vec![Action::AdminLogin {
                from: m.from.clone(),
                password: text.to_owned(),
                then: Some(then),
            }];
        }
        if let Some(body) = admin::strip_prefix(text) {
            return self.admin_text(dir, &m, body);
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
        if m.name.is_some() {
            self.contacts.entry(m.from.clone()).or_default().name = m.name.clone();
        }
        if !self.authorized(dir, &m.from) {
            return self.gate(dir, &m, text);
        }
        if let Some(lang) = user_help_command(text) {
            return vec![self.help_reply(dir, &m.from, lang)];
        }
        let mut out: Vec<Action> = self.welcome(&m.from).into_iter().collect();
        let welcomed = !out.is_empty();
        out.extend(self.route(dir, &m, text, welcomed));
        out
    }

    /// Steps 1–5 of "Choosing the field user" (docs/spec/pharos.md §2).
    fn route(
        &mut self,
        dir: &dyn Directory,
        m: &Incoming,
        text: &str,
        welcomed: bool,
    ) -> Vec<Action> {
        let now = m.now;
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
            return self.hold_and_ask(dir, m, text, true);
        }
        // 4. Sticky target.
        if let Some((k, t)) = c.target
            && now.saturating_sub(t) < self.cfg.target_timeout_secs
            && !has_held
        {
            return self.queue(dir, &m.from, k, text.to_owned(), m.msg_ref.clone(), now);
        }
        // 5. Ask.
        let mut out = self.hold_and_ask(dir, m, text, false);
        if c.talked.is_empty() && !welcomed {
            out.push(Self::reply(&m.from, HELP));
        }
        out
    }

    /// A field user's message for a bridged contact: the text to send there,
    /// after the welcome the first time. Writing first authorises the contact
    /// to answer.
    pub fn on_outgoing(
        &mut self,
        from: PublicKey,
        alias: &str,
        to: &Address,
        message: &Message,
        now: u64,
    ) -> Vec<Action> {
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
        let mut out: Vec<Action> = self.welcome(to).into_iter().collect();
        out.push(Self::reply(to, text.trim_end_matches(". ").to_owned()));
        out
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
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Dir {
        users: Vec<(PublicKey, &'static str, bool)>,
        switches: Switches,
        admins: Vec<Address>,
        requested: Vec<Address>,
        login: bool,
    }

    impl Directory for Dir {
        fn by_alias(&self, alias: &str) -> Option<PublicKey> {
            self.users
                .iter()
                .find(|(_, a, _)| a.eq_ignore_ascii_case(alias))
                .map(|(k, _, _)| *k)
        }
        fn alias_of(&self, key: &PublicKey) -> Option<String> {
            self.users
                .iter()
                .find(|(k, _, _)| k == key)
                .map(|(_, a, _)| a.to_string())
        }
        fn is_listening(&self, key: &PublicKey, _now: u64) -> bool {
            self.users.iter().any(|(k, _, l)| k == key && *l)
        }
        fn switches(&self) -> Switches {
            self.switches
        }
        fn is_admin(&self, a: &Address) -> bool {
            self.admins.contains(a)
        }
        fn requested(&self, a: &Address) -> bool {
            self.requested.contains(a)
        }
        fn login_available(&self) -> bool {
            self.login
        }
    }

    const ANA: PublicKey = PublicKey([1; 32]);
    const BEA: PublicKey = PublicKey([2; 32]);
    const T: u64 = 1_800_000_000;

    fn mama() -> Address {
        Address::SignalPhone("34611111111".into())
    }

    fn switches(open: bool, requests: bool) -> Switches {
        let mut s = Switches::default();
        s.set(Switch::Open, open);
        s.set(Switch::Requests, requests);
        s
    }

    /// Closed, no requests, no login.
    fn dir() -> Dir {
        Dir {
            users: vec![(ANA, "ana", true), (BEA, "bea", false)],
            switches: switches(false, false),
            admins: vec![],
            requested: vec![],
            login: false,
        }
    }

    /// Mamá is an authorised contact.
    fn router() -> Router {
        let mut r = Router::new(RouterCfg::default(), RouterState::default());
        r.authorize(&mama());
        r
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
        let d = Dir {
            users: vec![(ANA, "ana", true)],
            ..dir()
        };
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
        assert!(a.contains(&Action::Withdraw { from: mama() }));
        assert!(replies(&a).contains("BORRA TU MENSAJE CON EL CÓDIGO"));
        assert!(replies(&a).contains("👋"), "welcome after the code");
        assert!(replies(&a).contains("¿Para quién es?"));
        assert!(r.state().authorized.contains(&mama()));
        assert_eq!(
            queued(&r.on_incoming(&d, msg("ana", T + 3))),
            vec![(ANA, "¿todo bien?".into())]
        );
    }

    #[test]
    fn too_many_wrong_codes_silence_the_bot_for_an_hour() {
        let d = dir();
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
    fn closed_without_requests_or_code_and_writing_first() {
        let d = dir();
        let mut r = with(RouterCfg::default());
        assert!(replies(&r.on_incoming(&d, msg("@ana hola", T))).contains("no se ha enviado"));
        assert!(
            r.on_incoming(&d, msg("@ana hola", T + 1)).is_empty(),
            "one refusal note per hour"
        );
        r.on_outgoing(ANA, "ana", &mama(), &Message::Text("Hola".into()), T + 2);
        assert_eq!(queued(&r.on_incoming(&d, msg("Hola hija", T + 3))).len(), 1);
    }

    #[test]
    fn open_pharos_takes_anyone() {
        let d = Dir {
            switches: switches(true, false),
            ..dir()
        };
        let mut r = with(RouterCfg::default());
        assert_eq!(queued(&r.on_incoming(&d, msg("@ana hola", T))).len(), 1);
    }

    #[test]
    fn unknown_contact_requests_access_and_its_message_waits() {
        let mut d = Dir {
            switches: switches(false, true),
            ..dir()
        };
        let mut r = with(RouterCfg::default());
        let a = r.on_incoming(&d, msg("¿Llegasteis?", T));
        assert_eq!(
            a,
            vec![Action::RequestAccess {
                from: mama(),
                name: Some("Mamá".into())
            }]
        );
        d.requested.push(mama());
        assert!(replies(&r.on_incoming(&d, msg("¿Hola?", T + 5))).contains("sigue pendiente"));
        assert!(
            r.on_incoming(&d, msg("¿Hola?", T + 6)).is_empty(),
            "one note an hour"
        );
        let a = r.on_request_decided(&mama(), true);
        assert!(replies(&a).contains("aceptada"), "{a:?}");
        assert!(
            replies(&a).contains("¿Para quién es"),
            "held messages wait for an alias"
        );
        d.requested.clear();
        assert_eq!(
            queued(&r.on_incoming(&d, msg("ana", T + 10))),
            vec![
                (ANA, "¿Llegasteis?".into()),
                (ANA, "¿Hola?".into()),
                (ANA, "¿Hola?".into())
            ]
        );
    }

    #[test]
    fn refused_contacts_are_ignored_until_allowed() {
        let d = Dir {
            switches: switches(false, true),
            ..dir()
        };
        let mut r = with(RouterCfg::default());
        r.on_incoming(&d, msg("hola", T));
        assert!(replies(&r.on_request_decided(&mama(), false)).contains("no ha sido aceptada"));
        assert!(r.on_incoming(&d, msg("hola", T + 5)).is_empty());
        r.authorize(&mama());
        assert!(!r.on_incoming(&d, msg("@ana hola", T + 6)).is_empty());
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
        let Some(Action::Queue { token, .. }) =
            a.iter().find(|x| matches!(x, Action::Queue { .. }))
        else {
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
    fn admin_texts_go_to_the_executor() {
        let d = Dir {
            admins: vec![mama()],
            ..dir()
        };
        let mut r = router();
        assert_eq!(
            r.on_incoming(&d, msg("/admin accept @bea", T)),
            vec![Action::Admin {
                from: mama(),
                text: Text::Decide {
                    alias: "bea".into(),
                    accept: true
                }
            }]
        );
        assert_eq!(
            r.on_incoming(&d, msg("/admin set emergency on", T)),
            vec![Action::Admin {
                from: mama(),
                text: Text::Command(Command::Set(Switch::Emergency, true))
            }]
        );
        // Without /admin, a command word is ordinary chat.
        assert!(replies(&r.on_incoming(&d, msg("status", T + 1))).contains("¿Para quién es?"));
    }

    #[test]
    fn non_admins_are_asked_for_the_password_then_it_runs() {
        let d = Dir {
            login: true,
            ..dir()
        };
        let mut r = router();
        let a = r.on_incoming(&d, msg("/admin status", T));
        assert_eq!(
            replies(&a),
            "To manage this Pharos, send me its admin password."
        );
        assert_eq!(
            r.on_incoming(&d, msg("faro viejo de la costa", T + 5)),
            vec![Action::AdminLogin {
                from: mama(),
                password: "faro viejo de la costa".into(),
                then: Some(Text::Command(Command::Status)),
            }]
        );
        // The wait is over either way: the next text is chat.
        assert!(queued(&r.on_incoming(&d, msg("@ana hola", T + 6))).len() == 1);
        assert!(matches!(
            r.on_incoming(&d, msg("/admin login faro", T + 7))
                .as_slice(),
            [Action::AdminLogin { then: None, .. }]
        ));
    }

    #[test]
    fn without_password_login_only_admins_manage() {
        let (mut r, d) = (router(), dir());
        assert_eq!(
            replies(&r.on_incoming(&d, msg("/admin status", T))),
            "You are not an admin of this Pharos."
        );
        assert!(replies(&r.on_incoming(&d, msg("hola", T + 1))).contains("¿Para quién es?"));
    }

    #[test]
    fn welcome_and_help_are_chatty_on_signal_and_short_on_meshcore() {
        let (mut r, d) = (router(), dir());
        let a = r.on_incoming(&d, msg("hola", T));
        assert!(replies(&a).starts_with("👋 Hola, soy un Pharos"), "{a:?}");
        let a = r.on_incoming(&d, msg("/ayuda", T + 1));
        assert!(
            replies(&a).starts_with("• Para escribir a alguien"),
            "{a:?}"
        );
        assert!(queued(&a).is_empty());
        let a = r.on_incoming(&d, msg("/HELP", T + 2));
        assert!(replies(&a).starts_with("• To write to someone"), "{a:?}");
        let a = r.on_incoming(&d, msg("/ajuda", T + 3));
        assert!(replies(&a).starts_with("• Per a escriure a algú"), "{a:?}");

        let open = Dir {
            switches: switches(true, false),
            ..dir()
        };
        // Asking for help first in English: no Spanish welcome afterwards.
        let mut other = msg("/help", T);
        other.from = Address::WhatsappPhone("34622222222".into());
        let mut r = with(RouterCfg::default());
        r.on_incoming(&open, other.clone());
        other.text = Some("@ana hi".into());
        assert!(!replies(&r.on_incoming(&open, other)).contains("👋"));

        let mesh = Address::MeshcoreContact([7; 32]);
        let mut r = with(RouterCfg::default());
        let mut m = msg("/ayuda", T);
        m.from = mesh.clone();
        assert_eq!(replies(&r.on_incoming(&open, m)), HELP);
        let mut m = msg("hola", T + 1);
        m.from = mesh;
        assert!(
            !replies(&r.on_incoming(&open, m)).contains("👋"),
            "no welcome on MeshCore"
        );
    }

    #[test]
    fn help_is_not_held_as_a_message_behind_the_access_code() {
        let d = dir();
        let mut r = with(RouterCfg {
            access_code: Some("caballo pila grapa".into()),
            ..RouterCfg::default()
        });
        assert!(replies(&r.on_incoming(&d, msg("/ayuda", T))).contains("código de acceso"));
        let a = r.on_incoming(&d, msg("caballo pila grapa", T + 1));
        assert!(
            !replies(&a).contains("¿Para quién es?"),
            "nothing held: {a:?}"
        );
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
        assert_eq!(
            a.last(),
            Some(&Router::reply(&mama(), "ana: Hemos llegado"))
        );
        assert!(replies(&a).starts_with("👋 Hola"), "welcome first: {a:?}");
        let p = keryx::position::Position {
            lat: 42.6,
            lon: 0.6,
        };
        let a = r.on_outgoing(ANA, "ana", &mama(), &Message::Sos(Some(p)), T);
        let [Action::Reply { text, .. }] = a.as_slice() else {
            panic!("one reply, no second welcome: {a:?}")
        };
        assert!(
            text.starts_with("🆘 SOS de ana. https://maps.google.com/?q=42.60000,0.60000"),
            "{text}"
        );
    }
}
