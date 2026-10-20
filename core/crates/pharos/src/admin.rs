//! Remote management (decision 0020): quick admin commands from a bridge
//! account or a field user over the radio. Everything else is done over SSH.
//!
//! Commands start with `/admin` (optional over the radio, where every text to
//! the Pharos is a command). Commands and replies are English:
//!
//! ```text
//! /admin [help]                      list the commands (bridges only)
//! /admin status                      clients, pending, queued
//! /admin clients                     every field user and status
//! /admin admins                      who administers this Pharos
//! /admin accept @alias               admit a pending registration
//! /admin refuse @alias               refuse it
//! /admin allow number                authorise a bridged contact
//! /admin block @alias | number       ignore a field user or contact
//! /admin unblock @alias | number
//! /admin revoke @alias | number      drop admin rights granted by password
//! /admin login password              become admin
//! ```
//!
//! A number is a phone with country code (`+34…`, on the admin's own network
//! unless `signal`/`whatsapp`/`meshcore` is given first), a Signal username or
//! a MeshCore public key in hex. Over the radio the network is always named.
//!
//! Who may run them: admins listed in the config (set over SSH), or whoever
//! sends the admin password. On the bridges the bot asks for it
//! ([`crate::router`]). On the radio it only counts inside an encrypted frame
//! ([`RadioGate`]): signed plaintext would broadcast it.

use crate::registry::Status;
use crate::router::{Action, Router};
use crate::station::{Effect, Revoked, Station};
use keryx::bridge::{Address, network};
use keryx::{Message, PublicKey};
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Target {
    Alias(String),
    Contact(Address),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    Help,
    Status,
    Clients,
    Admins,
    Accept(String),
    Refuse(String),
    Block(Target),
    Unblock(Target),
    Allow(Address),
    Revoke(Target),
    /// The admin password.
    Login(String),
}

/// Admin help for Signal and WhatsApp, where the bot can be chatty. Not sent
/// over the radio: the app has its own.
pub const HELP: &str = "Admin commands (start each with /admin):
• status — summary of this Pharos
• clients — field users and when they were last heard
• accept @alias · refuse @alias — pending registrations
• allow number — let a contact write through this Pharos
• block @alias|number · unblock @alias|number
• admins — who manages it · revoke @alias|number
Everything else (access mode, codes, bridges, frequencies) over SSH.";

/// Admin help for MeshCore: every character counts.
pub const HELP_SHORT: &str = "status · clients · admins · accept/refuse @alias · \
allow nº · block/unblock @alias|nº · revoke @alias|nº";

/// The verb, if it is one. English only (decision 0020, revised).
fn verb(word: &str) -> Option<&'static str> {
    const VERBS: [&str; 11] = [
        "help", "status", "clients", "admins", "accept", "refuse", "block", "unblock", "allow",
        "revoke", "login",
    ];
    let w = word.to_lowercase();
    VERBS.into_iter().find(|v| *v == w)
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

/// Parse a contact: phone, Signal username or MeshCore key. `net` is the
/// network to assume for phone numbers (the admin's own).
fn contact(words: &[&str], net: Option<u8>) -> Option<Address> {
    let (net, arg) = match words {
        [n, a] => (
            match n.to_lowercase().as_str() {
                "signal" => Some(network::SIGNAL_PHONE),
                "whatsapp" => Some(network::WHATSAPP_PHONE),
                "meshcore" => Some(network::MESHCORE_CONTACT),
                _ => return None,
            },
            *a,
        ),
        [a] => (net, *a),
        _ => return None,
    };
    let digits = Address::normalize_phone(arg);
    let phone_like =
        arg.starts_with('+') || (!arg.is_empty() && arg.chars().all(|c| c.is_ascii_digit()));
    if arg.len() == 64 && arg.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut k = [0u8; 32];
        for (i, b) in k.iter_mut().enumerate() {
            *b = u8::from_str_radix(&arg[2 * i..2 * i + 2], 16).ok()?;
        }
        return Some(Address::MeshcoreContact(k));
    }
    if phone_like {
        return match net? {
            network::SIGNAL_PHONE | network::SIGNAL_USERNAME => Some(Address::SignalPhone(digits)),
            network::WHATSAPP_PHONE => Some(Address::WhatsappPhone(digits)),
            _ => None,
        };
    }
    (arg.contains('.') && matches!(net, Some(network::SIGNAL_PHONE | network::SIGNAL_USERNAME)))
        .then(|| Address::SignalUsername(arg.to_owned()))
}

fn target(words: &[&str], net: Option<u8>) -> Option<Target> {
    if let [a] = words
        && let Some(alias) = a.strip_prefix('@')
    {
        return Some(Target::Alias(alias.to_owned()));
    }
    contact(words, net)
        .map(Target::Contact)
        .or_else(|| match words {
            [a] => Some(Target::Alias((*a).to_owned())),
            _ => None,
        })
}

/// `None`: not a command (an ordinary message). The `/admin` prefix is
/// optional here; with it, anything unrecognised gives `Help`, as does a
/// known verb with bad arguments.
pub fn parse(text: &str, net: Option<u8>) -> Option<Command> {
    let (explicit, body) = match strip_prefix(text) {
        Some(rest) => (true, rest),
        None => (false, text.trim()),
    };
    let words: Vec<&str> = body.split_whitespace().collect();
    let Some((first, args)) = words.split_first() else {
        return explicit.then_some(Command::Help);
    };
    let Some(verb) = verb(first) else {
        return explicit.then_some(Command::Help);
    };
    let alias = || match args {
        [a] => Some(a.trim_start_matches('@').to_owned()),
        _ => None,
    };
    let cmd = match verb {
        "help" => Some(Command::Help),
        "status" => Some(Command::Status),
        "clients" => Some(Command::Clients),
        "admins" => Some(Command::Admins),
        "accept" => alias().map(Command::Accept),
        "refuse" => alias().map(Command::Refuse),
        "block" => target(args, net).map(Command::Block),
        "unblock" => target(args, net).map(Command::Unblock),
        "allow" => contact(args, net).map(Command::Allow),
        "revoke" => target(args, net).map(Command::Revoke),
        "login" => body
            .split_once(char::is_whitespace)
            .map(|(_, pw)| pw.trim())
            .filter(|pw| !pw.is_empty())
            .map(|pw| Command::Login(pw.to_owned())),
        _ => None,
    };
    Some(cmd.unwrap_or(Command::Help))
}

fn status_word(s: Status) -> &'static str {
    match s {
        Status::Registered => "registered",
        Status::Pending => "pending",
        Status::Refused => "refused",
        Status::Unregistered => "unregistered",
        Status::Blocked => "blocked",
    }
}

/// Carry out a command for someone already known to be an admin. Returns the
/// reply text and any station effects (for example the notice an approved
/// client gets). `terse`: MeshCore or the radio, where replies must be short.
pub fn execute(
    cmd: Command,
    station: &mut Station,
    router: &mut Router,
    now: u64,
    terse: bool,
) -> (String, Vec<Effect>) {
    let find = |s: &Station, a: &str| s.registry().find(a);
    match cmd {
        Command::Help => ((if terse { HELP_SHORT } else { HELP }).to_owned(), vec![]),
        Command::Login(_) => ("You are an admin already.".to_owned(), vec![]),
        Command::Status => {
            let count = |st: Status| {
                station
                    .registry()
                    .iter()
                    .filter(|(_, c)| c.status == st)
                    .count()
            };
            let queued: usize = station
                .registry()
                .iter()
                .map(|(k, _)| station.state().outbox.len(k))
                .sum();
            let listening = station
                .registry()
                .iter()
                .filter(|(_, c)| c.is_listening(now))
                .count();
            (
                format!(
                    "Registered: {}. Pending: {}. Listening: {listening}. Queued messages: {queued}. Authorised contacts: {}.",
                    count(Status::Registered),
                    count(Status::Pending),
                    router.state().authorized.len(),
                ),
                vec![],
            )
        }
        Command::Clients => {
            let lines: Vec<String> = station
                .registry()
                .iter()
                .map(|(_, c)| {
                    let heard = if c.last_heard == 0 {
                        "never heard".to_owned()
                    } else {
                        format!("heard {} min ago", now.saturating_sub(c.last_heard) / 60)
                    };
                    format!("@{} — {} — {heard}", c.alias, status_word(c.status))
                })
                .collect();
            let text = if lines.is_empty() {
                "No clients.".to_owned()
            } else {
                lines.join("\n")
            };
            (text, vec![])
        }
        Command::Admins => {
            let mut names: Vec<String> = station
                .registry()
                .iter()
                .filter(|(_, c)| c.is_admin())
                .map(|(_, c)| {
                    let how = if c.admin { "config" } else { "password" };
                    format!("@{} ({how})", c.alias)
                })
                .collect();
            let (config, granted) = router.admins();
            names.extend(config.iter().map(|a| format!("{} (config)", a.display())));
            names.extend(
                granted
                    .iter()
                    .map(|a| format!("{} (password)", a.display())),
            );
            (format!("Admins: {}.", names.join(", ")), vec![])
        }
        Command::Accept(a) | Command::Refuse(a) if find(station, &a).is_none() => {
            (format!("Unknown: @{a}."), vec![])
        }
        Command::Accept(a) => {
            let k = find(station, &a).unwrap();
            let fx = station.approve(&k, now).unwrap_or_default();
            (format!("@{a} accepted."), fx)
        }
        Command::Refuse(a) => {
            let k = find(station, &a).unwrap();
            let fx = station.refuse(&k, now).unwrap_or_default();
            (format!("@{a} refused."), fx)
        }
        Command::Block(Target::Alias(a)) => match find(station, &a) {
            Some(k) => {
                let _ = station.block(&k);
                (format!("@{a} blocked."), vec![])
            }
            None => (format!("Unknown: @{a}."), vec![]),
        },
        Command::Unblock(Target::Alias(a)) => match find(station, &a) {
            Some(k) => {
                let _ = station.unblock(&k);
                (format!("@{a} unblocked."), vec![])
            }
            None => (format!("Unknown: @{a}."), vec![]),
        },
        Command::Block(Target::Contact(c)) => {
            router.block(&c);
            (format!("{} blocked.", c.display()), vec![])
        }
        Command::Unblock(Target::Contact(c)) => {
            router.unblock(&c);
            (format!("{} unblocked.", c.display()), vec![])
        }
        Command::Allow(c) => {
            router.authorize(&c);
            (format!("{} allowed.", c.display()), vec![])
        }
        Command::Revoke(t) => {
            let (who, r) = match t {
                Target::Alias(a) => match find(station, &a) {
                    Some(k) => (format!("@{a}"), station.revoke_admin(&k).ok()),
                    None => return (format!("Unknown: @{a}."), vec![]),
                },
                Target::Contact(c) => (c.display(), Some(router.revoke_admin(&c))),
            };
            let text = match r {
                Some(Revoked::Done) => format!("{who} is no longer an admin."),
                Some(Revoked::InConfig) => {
                    format!("{who} is an admin in the config: remove them over SSH.")
                }
                _ => format!("{who} is not an admin."),
            };
            (text, vec![])
        }
    }
}

/// Wrong passwords per sender in the last hour; true when locked out.
pub(crate) fn locked_out(wrong: &mut Vec<u64>, now: u64, max: usize) -> bool {
    wrong.retain(|t| now.saturating_sub(*t) < 3600);
    wrong.len() >= max
}

/// What an admin text over the radio produced.
#[derive(Debug, Default)]
pub struct Outcome {
    /// Station effects, including the queued reply to the field user.
    pub effects: Vec<Effect>,
    /// Bridge messages (alerts to the other admins).
    pub actions: Vec<Action>,
}

/// Admin commands from field users over the radio. Plain text to the Pharos
/// from an admin client is a command. The password (`login …`) is accepted
/// only inside an encrypted frame; in signed plaintext it went out in clear,
/// so it is refused and, if it was right, the admins are told to change it.
pub struct RadioGate {
    password: Option<String>,
    max_attempts: usize,
    wrong: BTreeMap<PublicKey, Vec<u64>>,
}

impl RadioGate {
    pub fn new(password: Option<String>) -> Self {
        Self {
            password: password.filter(|p| !p.trim().is_empty()),
            max_attempts: 5,
            wrong: BTreeMap::new(),
        }
    }

    fn right(&self, pw: &str) -> bool {
        self.password
            .as_deref()
            .is_some_and(|p| p.trim() == pw.trim())
    }

    /// A text message from field user `from` to the Pharos itself.
    pub fn on_text(
        &mut self,
        station: &mut Station,
        router: &mut Router,
        from: PublicKey,
        text: &str,
        encrypted: bool,
        now: u64,
    ) -> Outcome {
        let Some(cmd) = parse(text, None) else {
            return Outcome::default();
        };
        let Some(client) = station.registry().get(&from) else {
            return Outcome::default();
        };
        let (alias, admin) = (client.alias.clone(), client.is_admin());
        let mut out = Outcome::default();
        let reply = match cmd {
            Command::Login(_) if admin => "You are an admin already.".to_owned(),
            Command::Login(pw) => {
                let wrong = self.wrong.entry(from).or_default();
                if locked_out(wrong, now, self.max_attempts) {
                    return out;
                }
                let right = self.right(&pw);
                if !right {
                    self.wrong.entry(from).or_default().push(now);
                }
                match (encrypted, right) {
                    (true, true) => {
                        let _ = station.grant_admin(&from);
                        out.actions = router.alert_admins(
                            &format!("@{alias} is now an admin (password over radio, encrypted)."),
                            None,
                        );
                        "You are an admin now.".to_owned()
                    }
                    (false, true) => {
                        out.actions = router.alert_admins(
                            &format!(
                                "⚠️ @{alias} sent the admin password UNENCRYPTED over the radio. Change it over SSH."
                            ),
                            None,
                        );
                        "PASSWORD SENT IN CLEAR: refused. Change it over SSH.".to_owned()
                    }
                    (false, false) => "Encrypted only. Wrong password.".to_owned(),
                    (true, false) => "Wrong password.".to_owned(),
                }
            }
            _ if !admin => "You are not an admin.".to_owned(),
            // No admin help on air: the app has it.
            Command::Help => return out,
            cmd => {
                let (text, fx) = execute(cmd, station, router, now, true);
                out.effects = fx;
                text
            }
        };
        if let Ok(fx) = station.queue(&from, Message::Text(reply), None, now) {
            out.effects.extend(fx);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_commands_and_ignores_chat() {
        let sig = Some(network::SIGNAL_PHONE);
        assert_eq!(parse("hola", sig), None);
        assert_eq!(parse("Status", sig), Some(Command::Status));
        assert_eq!(parse("/admin", sig), Some(Command::Help));
        assert_eq!(parse("/ADMIN status", sig), Some(Command::Status));
        assert_eq!(parse("/admin qué tal", sig), Some(Command::Help));
        assert_eq!(parse("/administrar", sig), None);
        assert_eq!(parse("estado", sig), None, "English only");
        assert_eq!(
            parse("/admin accept @bea", sig),
            Some(Command::Accept("bea".into()))
        );
        assert_eq!(parse("accept", sig), Some(Command::Help));
        assert_eq!(
            parse("allow +34 600 123 456", None),
            Some(Command::Help),
            "split number: one argument only"
        );
        assert_eq!(
            parse("allow +34600123456", sig),
            Some(Command::Allow(Address::SignalPhone("34600123456".into())))
        );
        assert_eq!(
            parse("/admin allow whatsapp +34600123456", None),
            Some(Command::Allow(Address::WhatsappPhone("34600123456".into())))
        );
        assert_eq!(
            parse("block @ana", None),
            Some(Command::Block(Target::Alias("ana".into())))
        );
        assert_eq!(
            parse("block mama.01", sig),
            Some(Command::Block(Target::Contact(Address::SignalUsername(
                "mama.01".into()
            ))))
        );
        assert_eq!(
            parse("allow +34600123456", None),
            Some(Command::Help),
            "radio admins name the network"
        );
        assert_eq!(
            parse("/admin login  caballo pila grapa ", sig),
            Some(Command::Login("caballo pila grapa".into()))
        );
        assert_eq!(parse("/admin login", sig), Some(Command::Help));
        assert_eq!(
            parse("/admin revoke @bea", sig),
            Some(Command::Revoke(Target::Alias("bea".into())))
        );
    }
}
