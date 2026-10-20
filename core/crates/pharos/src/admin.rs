//! Remote management (decisions 0020, 0021). One executor for both channels:
//! field users send binary [`keryx::admin::Command`]s over the radio and get
//! structured [`Reply`]s; bridge admins type English text commands and get
//! English text. Everything else is done over SSH (`pharos` CLI).
//!
//! Bridge commands start with `/admin`:
//!
//! ```text
//! /admin [help]                    list the commands
//! /admin status                    switches and counters
//! /admin requests                  pending access requests, numbered
//! /admin accept <n|@alias>         admit a request
//! /admin refuse <n|@alias>         refuse it
//! /admin clients                   field users and when they were heard
//! /admin admins                    who manages this Pharos
//! /admin allow <number>            let a Signal/WhatsApp contact write
//! /admin block <@alias|number>     ignore a field user or contact
//! /admin unblock <@alias|number>
//! /admin grant <@alias|number>     make an admin
//! /admin revoke <@alias|number>    remove an admin (SSH ones too)
//! /admin set <switch> on|off       open, requests, login, emergency, stealth
//! /admin login <password>          become admin
//! ```
//!
//! A number is a phone with country code (on the admin's own network unless
//! `signal`/`whatsapp` comes first), a Signal username, or the first 8 hex
//! digits of a MeshCore key the Pharos knows.
//!
//! Who may run them: admins in the access state. Others may only `login`,
//! with the admin password, while the `login` switch is on: on the bridges,
//! or over the radio inside an encrypted frame. In plain it is refused even
//! when right, and the admins are told to change it.

use crate::access::Who;
use crate::registry::Status;
use crate::router::{Action, Router};
use crate::station::{Effect, Station};
use keryx::admin::{
    AdminEntry, ClientEntry, Command, Outcome, Reply, RequestEntry, Switch, Switches, Target, how,
    meshcore_prefix, who,
};
use keryx::bridge::{Address, network};
use keryx::{Message, PublicKey};
use std::collections::BTreeMap;

/// Help for Signal and WhatsApp, where the bot can be chatty. Not on air:
/// the app has its own.
pub const HELP: &str = "Admin commands (start each with /admin):
• status — switches and counters
• requests — pending access requests · accept n · refuse n
• clients — field users and when they were last heard
• allow number — let a Signal or WhatsApp contact write
• block @alias|number · unblock @alias|number
• admins · grant @alias|number · revoke @alias|number
• set open|requests|login|emergency|stealth on|off
Everything else over SSH.";

/// Help for MeshCore: every character counts.
pub const HELP_SHORT: &str = "status · requests · accept/refuse n · clients · allow nº · \
block/unblock @alias|nº · admins · grant/revoke @alias|nº · set <switch> on|off";

/// A bridge admin text: the help, or a command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Text {
    Help,
    Command(Command),
    /// `accept @alias` / `refuse @alias`: the request of that field user.
    Decide {
        alias: String,
        accept: bool,
    },
}

/// The text after a leading `/admin`, or `None` if it does not start so.
pub fn strip_prefix(text: &str) -> Option<&str> {
    let t = text.trim_start();
    let head = t.get(..6)?;
    let rest = &t[6..];
    (head.eq_ignore_ascii_case("/admin")
        && (rest.is_empty() || rest.starts_with(char::is_whitespace)))
    .then(|| rest.trim())
}

/// A contact: phone, Signal username, or MeshCore key prefix (8 hex digits).
/// `net` is the network to assume for phone numbers (the admin's own).
fn contact(words: &[&str], net: Option<u8>) -> Option<Target> {
    let (net, arg) = match words {
        [n, a] => (
            match n.to_lowercase().as_str() {
                "signal" => Some(network::SIGNAL_PHONE),
                "whatsapp" => Some(network::WHATSAPP_PHONE),
                _ => return None,
            },
            *a,
        ),
        [a] => (net, *a),
        _ => return None,
    };
    if arg.len() == 8 && arg.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut p = [0u8; 4];
        for (i, b) in p.iter_mut().enumerate() {
            *b = u8::from_str_radix(&arg[2 * i..2 * i + 2], 16).ok()?;
        }
        return Some(Target::MeshcorePrefix(p));
    }
    let phone_like =
        arg.starts_with('+') || (!arg.is_empty() && arg.chars().all(|c| c.is_ascii_digit()));
    if phone_like {
        let digits = Address::normalize_phone(arg);
        return match net? {
            network::SIGNAL_PHONE | network::SIGNAL_USERNAME => {
                Some(Target::Contact(Address::SignalPhone(digits)))
            }
            network::WHATSAPP_PHONE => Some(Target::Contact(Address::WhatsappPhone(digits))),
            _ => None,
        };
    }
    (arg.contains('.') && matches!(net, Some(network::SIGNAL_PHONE | network::SIGNAL_USERNAME)))
        .then(|| Target::Contact(Address::SignalUsername(arg.to_owned())))
}

fn target(words: &[&str], net: Option<u8>) -> Option<Target> {
    match words {
        [a] if a.starts_with('@') => Some(Target::Alias(a[1..].to_owned())),
        _ => contact(words, net),
    }
}

fn switch(word: &str) -> Option<Switch> {
    Some(match word.to_lowercase().as_str() {
        "open" => Switch::Open,
        "requests" => Switch::Requests,
        "login" => Switch::Login,
        "emergency" => Switch::Emergency,
        "stealth" => Switch::Stealth,
        _ => return None,
    })
}

/// Parse what follows `/admin`. Anything not understood gives the help.
pub fn parse(body: &str, net: Option<u8>) -> Text {
    let words: Vec<&str> = body.split_whitespace().collect();
    let Some((verb, args)) = words.split_first() else {
        return Text::Help;
    };
    let decide = |accept: bool| match args {
        [a] if a.starts_with('@') => Some(Text::Decide {
            alias: a[1..].to_owned(),
            accept,
        }),
        [n] => n.trim_start_matches('#').parse().ok().map(|n| {
            Text::Command(if accept {
                Command::Accept(n)
            } else {
                Command::Refuse(n)
            })
        }),
        _ => None,
    };
    let cmd = |c: Option<Command>| c.map(Text::Command);
    let parsed = match verb.to_lowercase().as_str() {
        "help" => Some(Text::Help),
        "status" => cmd(Some(Command::Status)),
        "requests" => cmd(Some(Command::Requests)),
        "accept" => decide(true),
        "refuse" => decide(false),
        "clients" => cmd(Some(Command::Clients)),
        "admins" => cmd(Some(Command::Admins)),
        "allow" => cmd(match contact(args, net) {
            Some(Target::Contact(a)) => Some(Command::Allow(a)),
            _ => None,
        }),
        "block" => cmd(target(args, net).map(Command::Block)),
        "unblock" => cmd(target(args, net).map(Command::Unblock)),
        "grant" => cmd(target(args, net).map(Command::AddAdmin)),
        "revoke" => cmd(target(args, net).map(Command::RemoveAdmin)),
        "set" => cmd(match args {
            [s, v] => switch(s).and_then(|s| match v.to_lowercase().as_str() {
                "on" => Some(Command::Set(s, true)),
                "off" => Some(Command::Set(s, false)),
                _ => None,
            }),
            _ => None,
        }),
        "login" => cmd(body
            .split_once(char::is_whitespace)
            .map(|(_, pw)| pw.trim())
            .filter(|pw| !pw.is_empty())
            .map(|pw| Command::Login(pw.to_owned()))),
        _ => None,
    };
    parsed.unwrap_or(Text::Help)
}

/// Who sent a command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Caller {
    /// A field user over the radio; `encrypted` is the frame's mode.
    Radio { key: PublicKey, encrypted: bool },
    /// A bridged account.
    Bridge(Address),
}

impl Caller {
    fn who(&self) -> Who {
        match self {
            Self::Radio { key, .. } => Who::Radio(*key),
            Self::Bridge(a) => Who::Contact(a.clone()),
        }
    }
}

/// What running a command produced.
#[derive(Debug, Default)]
pub struct Ran {
    /// Station effects, including replies queued on air.
    pub effects: Vec<Effect>,
    /// Bridge messages: the reply to a bridge admin, router follow-ups.
    pub actions: Vec<Action>,
}

/// Wrong passwords per sender per hour before the Pharos stops answering.
const MAX_WRONG: usize = 5;

/// The admin password and the wrong-password counters (not persisted).
pub struct Admin {
    password: Option<String>,
    wrong: BTreeMap<Who, Vec<u64>>,
}

/// One-line English rendering of a [`Reply`] for bridge admins.
pub fn render(r: &Reply) -> String {
    match r {
        Reply::Done {
            opcode,
            outcome,
            subject,
        } => {
            let s = subject.as_deref().unwrap_or("");
            match (outcome, *opcode) {
                (Outcome::Ok, 2) => format!("Accepted: {s}."),
                (Outcome::Ok, 3) => format!("Refused: {s}."),
                (Outcome::Ok, 11) => "Right password: you are an admin now.\n⚠️ DELETE THE MESSAGE WITH THE PASSWORD NOW. I CANNOT DO IT.".into(),
                (Outcome::Ok, _) if s.is_empty() => "Done.".into(),
                (Outcome::Ok, _) => format!("Done: {s}."),
                (Outcome::NotAdmin, _) => "You are not an admin of this Pharos.".into(),
                (Outcome::Unknown, _) if s.is_empty() => "Unknown.".into(),
                (Outcome::Unknown, _) => format!("Unknown: {s}."),
                (Outcome::WrongPassword, _) => "Wrong password. Start again with /admin.".into(),
                (Outcome::PasswordInClear, _) => "PASSWORD SENT IN CLEAR: refused. Change it over SSH.".into(),
                (Outcome::LastAdmin, _) => "That is the last admin and password login is off: refused.".into(),
                (Outcome::LoginOff, _) => "Password login is off on this Pharos.".into(),
                (Outcome::Locked, _) => "Too many wrong passwords: try again in an hour.".into(),
                (Outcome::Refused, _) => "Refused.".into(),
            }
        }
        Reply::Status {
            switches,
            registered,
            requests,
            listening,
            queued,
            contacts,
        } => format!(
            "{}. Registered: {registered}. Requests: {requests}. Listening: {listening}. Queued: {queued}. Contacts: {contacts}.",
            switch_names(*switches)
        ),
        Reply::Requests(l) if l.is_empty() => "No pending requests.".into(),
        Reply::Requests(l) => l
            .iter()
            .map(|e| format!("#{} {} — {}", e.num, network_name(e.who), e.name))
            .collect::<Vec<_>>()
            .join("\n"),
        Reply::Clients(l) if l.is_empty() => "No clients.".into(),
        Reply::Clients(l) => l
            .iter()
            .map(|e| {
                let status = [
                    "registered",
                    "pending",
                    "refused",
                    "unregistered",
                    "blocked",
                ]
                .get(usize::from(e.status))
                .unwrap_or(&"?");
                let heard = e
                    .heard_min
                    .map_or("never heard".to_owned(), |m| format!("heard {m} min ago"));
                format!("@{} — {status} — {heard}", e.alias)
            })
            .collect::<Vec<_>>()
            .join("\n"),
        Reply::Admins(l) => format!(
            "Admins: {}.",
            l.iter()
                .map(|e| {
                    let how = ["SSH", "by an admin", "password"]
                        .get(usize::from(e.how))
                        .unwrap_or(&"?");
                    format!("{} ({}, {how})", e.name, network_name(e.who))
                })
                .collect::<Vec<_>>()
                .join(", ")
        ),
        Reply::NewRequests { pending, .. } => {
            format!("New access request ({pending} pending). /admin requests to see them.")
        }
        Reply::SwitchesChanged(s) => format!("Switches changed: {}.", switch_names(*s)),
        Reply::AdminChanged { added, who, name } => format!(
            "{name} ({}) {} an admin.",
            network_name(*who),
            if *added { "is now" } else { "is no longer" }
        ),
    }
}

fn network_name(w: u8) -> &'static str {
    match w {
        who::RADIO => "radio",
        who::SIGNAL => "Signal",
        who::WHATSAPP => "WhatsApp",
        _ => "MeshCore",
    }
}

fn switch_names(s: Switches) -> String {
    Switch::ALL
        .iter()
        .map(|w| {
            let name = match w {
                Switch::Open => "open",
                Switch::Requests => "requests",
                Switch::Login => "login",
                Switch::Emergency => "emergency",
                Switch::Stealth => "stealth",
            };
            format!("{name} {}", if s.get(*w) { "on" } else { "off" })
        })
        .collect::<Vec<_>>()
        .join(", ")
}

/// How a bridged contact is named in replies and requests.
pub fn contact_name(a: &Address, profile: Option<&str>) -> String {
    match a {
        Address::MeshcoreContact(k) => {
            let p: String = meshcore_prefix(k)
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            format!("{} {p}", profile.unwrap_or("MeshCore"))
        }
        _ => profile.map_or_else(|| a.display(), |n| format!("{n} {}", a.display())),
    }
}

impl Admin {
    pub fn new(password: Option<String>) -> Self {
        Self {
            password: password
                .map(|p| p.trim().to_owned())
                .filter(|p| !p.is_empty()),
            wrong: BTreeMap::new(),
        }
    }

    /// The password exists and the `login` switch is on.
    pub fn login_available(&self, station: &Station) -> bool {
        self.password.is_some() && station.access().on(Switch::Login)
    }

    fn resolve(station: &Station, router: &Router, t: &Target) -> Option<Who> {
        match t {
            Target::Alias(a) => station.registry().find(a).map(Who::Radio),
            Target::Contact(c) => Some(Who::Contact(c.clone())),
            Target::MeshcorePrefix(p) => {
                let a = station.access();
                let mut found: Vec<Address> = a
                    .admins
                    .iter()
                    .map(|x| &x.who)
                    .chain(a.requests.iter().map(|r| &r.who))
                    .filter_map(|w| match w {
                        Who::Contact(a) => Some(a.clone()),
                        _ => None,
                    })
                    .chain(router.known_contacts())
                    .filter(
                        |a| matches!(a, Address::MeshcoreContact(k) if meshcore_prefix(k) == *p),
                    )
                    .collect();
                found.sort();
                found.dedup();
                // An ambiguous prefix resolves to nothing rather than the wrong node.
                match found.as_slice() {
                    [one] => Some(Who::Contact(one.clone())),
                    _ => None,
                }
            }
        }
    }

    fn name_of(station: &Station, router: &Router, w: &Who) -> String {
        match w {
            Who::Radio(k) => station
                .registry()
                .get(k)
                .map_or("?".into(), |c| format!("@{}", c.alias)),
            Who::Contact(a) => contact_name(a, router.profile_name(a).as_deref()),
        }
    }

    /// Run `cmd` for `caller` and deliver the reply: queued on air for a
    /// field user, as text for a bridge admin.
    pub fn run(
        &mut self,
        station: &mut Station,
        router: &mut Router,
        caller: &Caller,
        cmd: Command,
        now: u64,
    ) -> Ran {
        let mut ran = Ran::default();
        let me = caller.who();
        let opcode = cmd.opcode();
        let reply = if let Command::Login(pw) = &cmd {
            match self.login(station, router, caller, pw, now, &mut ran) {
                Some(outcome) => Reply::Done {
                    opcode,
                    outcome,
                    subject: None,
                },
                None => return ran, // locked out: silence
            }
        } else if !station.access().is_admin(&me) {
            Reply::Done {
                opcode,
                outcome: Outcome::NotAdmin,
                subject: None,
            }
        } else {
            self.execute(station, router, &me, cmd, now, &mut ran)
        };
        match caller {
            Caller::Radio { key, .. } => {
                if let Ok(fx) = station.queue(key, Message::AdminReply(reply), None, now) {
                    ran.effects.extend(fx);
                }
            }
            Caller::Bridge(a) => {
                let login_ok = matches!(
                    reply,
                    Reply::Done {
                        opcode: 11,
                        outcome: Outcome::Ok,
                        ..
                    }
                );
                let text = if Router::terse(a) && login_ok {
                    "You are an admin. ⚠️ DELETE THE MESSAGE WITH THE PASSWORD.".to_owned()
                } else {
                    render(&reply)
                };
                ran.actions.push(Action::Reply {
                    to: a.clone(),
                    text,
                });
            }
        }
        ran
    }

    /// `None`: locked out, answer nothing.
    fn login(
        &mut self,
        station: &mut Station,
        router: &Router,
        caller: &Caller,
        pw: &str,
        now: u64,
        ran: &mut Ran,
    ) -> Option<Outcome> {
        let me = caller.who();
        if station.access().is_admin(&me) {
            return Some(Outcome::Ok);
        }
        if !self.login_available(station) {
            return Some(Outcome::LoginOff);
        }
        let right = self.password.as_deref() == Some(pw.trim());
        let wrong = self.wrong.entry(me.clone()).or_default();
        if locked_out(wrong, now, MAX_WRONG) {
            return None;
        }
        if !right {
            wrong.push(now);
            return Some(if wrong.len() >= MAX_WRONG {
                Outcome::Locked
            } else {
                Outcome::WrongPassword
            });
        }
        wrong.clear();
        let name = Self::name_of(station, router, &me);
        if matches!(
            caller,
            Caller::Radio {
                encrypted: false,
                ..
            }
        ) {
            let alert = Reply::Done {
                opcode: Command::Login(String::new()).opcode(),
                outcome: Outcome::PasswordInClear,
                subject: Some(name),
            };
            ran.effects.extend(announce(station, &me, alert, now, true));
            return Some(Outcome::PasswordInClear);
        }
        station.access_mut().add_admin(me.clone(), how::PASSWORD);
        let changed = Reply::AdminChanged {
            added: true,
            who: me.network(),
            name,
        };
        ran.effects
            .extend(announce(station, &me, changed, now, false));
        Some(Outcome::Ok)
    }

    fn execute(
        &mut self,
        station: &mut Station,
        router: &mut Router,
        me: &Who,
        cmd: Command,
        now: u64,
        ran: &mut Ran,
    ) -> Reply {
        let opcode = cmd.opcode();
        let done = |outcome, subject: Option<String>| Reply::Done {
            opcode,
            outcome,
            subject,
        };
        let blocking = matches!(cmd, Command::Block(_));
        match cmd {
            Command::Login(_) => done(Outcome::Ok, None),
            Command::Status => {
                let reg = station.registry();
                let count = |st: Status| reg.iter().filter(|(_, c)| c.status == st).count();
                let queued: usize = reg.iter().map(|(k, _)| station.state().outbox.len(k)).sum();
                let listening = reg.iter().filter(|(_, c)| c.is_listening(now)).count();
                let n8 = |n: usize| n.min(255) as u8;
                Reply::Status {
                    switches: station.access().switches,
                    registered: n8(count(Status::Registered)),
                    requests: n8(station.access().requests.len()),
                    listening: n8(listening),
                    queued: queued.min(65535) as u16,
                    contacts: router.state().authorized.len().min(65535) as u16,
                }
            }
            Command::Requests => Reply::Requests(
                station
                    .access()
                    .requests
                    .iter()
                    .map(|r| RequestEntry {
                        num: r.num,
                        who: r.who.network(),
                        name: r.name.clone(),
                    })
                    .collect(),
            ),
            Command::Accept(n) | Command::Refuse(n) => {
                let accept = matches!(cmd, Command::Accept(_));
                let Some(r) = station.access_mut().take_request(n) else {
                    return done(Outcome::Unknown, Some(format!("#{n}")));
                };
                match &r.who {
                    Who::Radio(k) => {
                        let fx = if accept {
                            station.approve(k, now)
                        } else {
                            station.refuse(k, now)
                        };
                        ran.effects.extend(fx.unwrap_or_default());
                    }
                    Who::Contact(a) => ran.actions.extend(router.on_request_decided(a, accept)),
                }
                done(Outcome::Ok, Some(r.name))
            }
            Command::Clients => Reply::Clients(
                station
                    .registry()
                    .iter()
                    .map(|(_, c)| ClientEntry {
                        status: match c.status {
                            Status::Registered => 0,
                            Status::Pending => 1,
                            Status::Refused => 2,
                            Status::Unregistered => 3,
                            Status::Blocked => 4,
                        },
                        heard_min: (c.last_heard != 0)
                            .then(|| (now.saturating_sub(c.last_heard) / 60).min(65534) as u16),
                        alias: c.alias.clone(),
                    })
                    .collect(),
            ),
            Command::Admins => Reply::Admins(
                station
                    .access()
                    .admins
                    .iter()
                    .map(|a| AdminEntry {
                        who: a.who.network(),
                        how: a.how,
                        name: Self::name_of(station, router, &a.who),
                    })
                    .collect(),
            ),
            Command::Allow(a) => {
                router.authorize(&a);
                station.access_mut().drop_request(&Who::Contact(a.clone()));
                done(Outcome::Ok, Some(a.display()))
            }
            Command::Block(t) | Command::Unblock(t) => {
                let on = blocking;
                let Some(w) = Self::resolve(station, router, &t) else {
                    return done(Outcome::Unknown, None);
                };
                let name = Self::name_of(station, router, &w);
                match &w {
                    Who::Radio(k) => {
                        let _ = if on {
                            station.block(k)
                        } else {
                            station.unblock(k)
                        };
                    }
                    Who::Contact(a) if on => router.block(a),
                    Who::Contact(a) => router.unblock(a),
                }
                if on {
                    station.access_mut().drop_request(&w);
                }
                done(Outcome::Ok, Some(name))
            }
            Command::AddAdmin(t) => {
                let Some(w) = Self::resolve(station, router, &t) else {
                    return done(Outcome::Unknown, None);
                };
                if let Who::Contact(a) = &w {
                    router.authorize(a);
                }
                let name = Self::name_of(station, router, &w);
                if station.access_mut().add_admin(w.clone(), how::BY_ADMIN) {
                    let changed = Reply::AdminChanged {
                        added: true,
                        who: w.network(),
                        name: name.clone(),
                    };
                    ran.effects
                        .extend(announce(station, me, changed, now, false));
                }
                done(Outcome::Ok, Some(name))
            }
            Command::RemoveAdmin(t) => {
                let Some(w) = Self::resolve(station, router, &t) else {
                    return done(Outcome::Unknown, None);
                };
                let name = Self::name_of(station, router, &w);
                let keep_one = !self.login_available(station);
                match station.access_mut().remove_admin(&w, keep_one) {
                    Err(true) => done(Outcome::LastAdmin, Some(name)),
                    Err(false) => done(Outcome::Unknown, Some(name)),
                    Ok(()) => {
                        let changed = Reply::AdminChanged {
                            added: false,
                            who: w.network(),
                            name: name.clone(),
                        };
                        ran.effects
                            .extend(announce(station, me, changed, now, false));
                        done(Outcome::Ok, Some(name))
                    }
                }
            }
            Command::Set(s, on) => {
                if station.access().on(s) != on {
                    station.set_switch(s, on);
                    let sw = Reply::SwitchesChanged(station.access().switches);
                    ran.effects.extend(announce(station, me, sw, now, false));
                }
                done(Outcome::Ok, None)
            }
        }
    }
}

/// Tell every admin but `except`: field users on air, bridge admins as text.
/// Nothing in stealth, unless `critical` (a password exposed on air).
fn announce(
    station: &mut Station,
    except: &Who,
    r: Reply,
    now: u64,
    critical: bool,
) -> Vec<Effect> {
    if station.access().on(Switch::Stealth) && !critical {
        return Vec::new();
    }
    let to: Vec<Who> = station
        .access()
        .admins
        .iter()
        .map(|a| a.who.clone())
        .filter(|w| w != except)
        .collect();
    let text = match &r {
        Reply::Done {
            outcome: Outcome::PasswordInClear,
            subject,
            ..
        } => format!(
            "⚠️ {} sent the admin password UNENCRYPTED over the radio. Change it over SSH.",
            subject.as_deref().unwrap_or("Someone")
        ),
        other => render(other),
    };
    let mut out = Vec::new();
    for w in to {
        match w {
            Who::Radio(k) => out.extend(
                station
                    .queue(&k, Message::AdminReply(r.clone()), None, now)
                    .unwrap_or_default(),
            ),
            Who::Contact(a) => out.push(Effect::BridgeText {
                to: a,
                text: text.clone(),
            }),
        }
    }
    out
}

/// Wrong attempts per sender in the last hour; true when locked out.
pub(crate) fn locked_out(wrong: &mut Vec<u64>, now: u64, max: usize) -> bool {
    wrong.retain(|t| now.saturating_sub(*t) < 3600);
    wrong.len() >= max
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_english_text_commands() {
        let sig = Some(network::SIGNAL_PHONE);
        assert_eq!(strip_prefix("/admin"), Some(""));
        assert_eq!(strip_prefix("/ADMIN status"), Some("status"));
        assert_eq!(strip_prefix("/administrar"), None);
        assert_eq!(parse("", sig), Text::Help);
        assert_eq!(parse("estado", sig), Text::Help, "English only");
        assert_eq!(parse("Status", sig), Text::Command(Command::Status));
        assert_eq!(parse("accept 3", sig), Text::Command(Command::Accept(3)));
        assert_eq!(parse("refuse #4", sig), Text::Command(Command::Refuse(4)));
        assert_eq!(
            parse("accept @bea", sig),
            Text::Decide {
                alias: "bea".into(),
                accept: true
            }
        );
        assert_eq!(
            parse("allow +34600123456", sig),
            Text::Command(Command::Allow(Address::SignalPhone("34600123456".into())))
        );
        assert_eq!(
            parse("allow whatsapp +34600123456", None),
            Text::Command(Command::Allow(Address::WhatsappPhone("34600123456".into())))
        );
        assert_eq!(parse("allow +34 600 123 456", sig), Text::Help);
        assert_eq!(
            parse("allow a1b2c3d4", sig),
            Text::Help,
            "MeshCore only by request"
        );
        assert_eq!(
            parse("block a1b2c3d4", sig),
            Text::Command(Command::Block(Target::MeshcorePrefix([
                0xa1, 0xb2, 0xc3, 0xd4
            ])))
        );
        assert_eq!(
            parse("revoke @tio", sig),
            Text::Command(Command::RemoveAdmin(Target::Alias("tio".into())))
        );
        assert_eq!(
            parse("grant mama.01", sig),
            Text::Command(Command::AddAdmin(Target::Contact(Address::SignalUsername(
                "mama.01".into()
            ))))
        );
        assert_eq!(
            parse("set emergency ON", sig),
            Text::Command(Command::Set(Switch::Emergency, true))
        );
        assert_eq!(parse("set loud on", sig), Text::Help);
        assert_eq!(
            parse("login  faro viejo de la costa ", sig),
            Text::Command(Command::Login("faro viejo de la costa".into()))
        );
    }
}
