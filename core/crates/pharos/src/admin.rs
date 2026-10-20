//! Remote management (decision 0020): quick admin commands from a bridge
//! account or a field user over the radio. Everything else is done over SSH.
//!
//! Commands start with `/admin` (optional over the radio, where every text to
//! the Pharos is a command). Verbs are Spanish; the English ones work too:
//!
//! ```text
//! /admin [ayuda]                     list the commands            (help)
//! /admin estado                      clients, pending, queued     (status)
//! /admin clientes                    every field user and status  (clients)
//! /admin admins                      who administers this Pharos
//! /admin aceptar @alias              admit a pending registration (accept)
//! /admin rechazar @alias             refuse it                    (refuse)
//! /admin bloquear @alias | número    ignore a field user/contact  (block)
//! /admin desbloquear @alias | número                              (unblock)
//! /admin permitir número             authorise a bridged contact  (allow)
//! /admin revocar @alias | número     drop admin rights granted by password (revoke)
//! /admin acceder contraseña          become admin                 (login)
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

/// Help for Signal and WhatsApp, where the bot can be chatty.
pub const HELP: &str = "Órdenes de administración (empieza cada una con /admin):
• estado — resumen del Pharos
• clientes — usuarios de radio y cuándo se les oyó
• aceptar @alias · rechazar @alias — registros pendientes
• bloquear @alias|número · desbloquear @alias|número
• permitir número — autoriza a un contacto a escribir
• admins — quién administra · revocar @alias|número
Lo demás (modo de acceso, códigos, pasarelas, frecuencias), por SSH.";

/// Help for MeshCore and the radio: every character counts.
pub const HELP_SHORT: &str = "estado · clientes · admins · aceptar/rechazar @alias · \
bloquear/desbloquear @alias|nº · permitir nº · revocar @alias|nº";

/// Canonical (Spanish) verb for a Spanish or English one.
fn verb(word: &str) -> Option<&'static str> {
    Some(match word.to_lowercase().as_str() {
        "ayuda" | "help" => "ayuda",
        "estado" | "status" => "estado",
        "clientes" | "clients" => "clientes",
        "admins" => "admins",
        "aceptar" | "accept" => "aceptar",
        "rechazar" | "refuse" | "reject" => "rechazar",
        "bloquear" | "block" => "bloquear",
        "desbloquear" | "unblock" => "desbloquear",
        "permitir" | "allow" => "permitir",
        "revocar" | "revoke" => "revocar",
        "acceder" | "login" => "acceder",
        _ => return None,
    })
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
        "ayuda" => Some(Command::Help),
        "estado" => Some(Command::Status),
        "clientes" => Some(Command::Clients),
        "admins" => Some(Command::Admins),
        "aceptar" => alias().map(Command::Accept),
        "rechazar" => alias().map(Command::Refuse),
        "bloquear" => target(args, net).map(Command::Block),
        "desbloquear" => target(args, net).map(Command::Unblock),
        "permitir" => contact(args, net).map(Command::Allow),
        "revocar" => target(args, net).map(Command::Revoke),
        "acceder" => body
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
        Status::Registered => "registrado",
        Status::Pending => "pendiente",
        Status::Refused => "rechazado",
        Status::Unregistered => "sin registrar",
        Status::Blocked => "bloqueado",
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
        Command::Login(_) => ("Ya eres administrador.".to_owned(), vec![]),
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
                    "Registrados: {}. Pendientes: {}. A la escucha: {listening}. Mensajes en cola: {queued}. Contactos autorizados: {}.",
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
                        "nunca oído".to_owned()
                    } else {
                        format!("oído hace {} min", now.saturating_sub(c.last_heard) / 60)
                    };
                    format!("@{} — {} — {heard}", c.alias, status_word(c.status))
                })
                .collect();
            let text = if lines.is_empty() {
                "No hay clientes.".to_owned()
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
                    let how = if c.admin { "config" } else { "contraseña" };
                    format!("@{} ({how})", c.alias)
                })
                .collect();
            let (config, granted) = router.admins();
            names.extend(config.iter().map(|a| format!("{} (config)", a.display())));
            names.extend(
                granted
                    .iter()
                    .map(|a| format!("{} (contraseña)", a.display())),
            );
            (format!("Administradores: {}.", names.join(", ")), vec![])
        }
        Command::Accept(a) | Command::Refuse(a) if find(station, &a).is_none() => {
            (format!("No conozco a @{a}."), vec![])
        }
        Command::Accept(a) => {
            let k = find(station, &a).unwrap();
            let fx = station.approve(&k, now).unwrap_or_default();
            (format!("@{a} aceptado."), fx)
        }
        Command::Refuse(a) => {
            let k = find(station, &a).unwrap();
            let fx = station.refuse(&k, now).unwrap_or_default();
            (format!("@{a} rechazado."), fx)
        }
        Command::Block(Target::Alias(a)) => match find(station, &a) {
            Some(k) => {
                let _ = station.block(&k);
                (format!("@{a} bloqueado."), vec![])
            }
            None => (format!("No conozco a @{a}."), vec![]),
        },
        Command::Unblock(Target::Alias(a)) => match find(station, &a) {
            Some(k) => {
                let _ = station.unblock(&k);
                (format!("@{a} desbloqueado."), vec![])
            }
            None => (format!("No conozco a @{a}."), vec![]),
        },
        Command::Block(Target::Contact(c)) => {
            router.block(&c);
            (format!("{} bloqueado.", c.display()), vec![])
        }
        Command::Unblock(Target::Contact(c)) => {
            router.unblock(&c);
            (format!("{} desbloqueado.", c.display()), vec![])
        }
        Command::Allow(c) => {
            router.authorize(&c);
            (format!("{} autorizado.", c.display()), vec![])
        }
        Command::Revoke(t) => {
            let (who, r) = match t {
                Target::Alias(a) => match find(station, &a) {
                    Some(k) => (format!("@{a}"), station.revoke_admin(&k).ok()),
                    None => return (format!("No conozco a @{a}."), vec![]),
                },
                Target::Contact(c) => (c.display(), Some(router.revoke_admin(&c))),
            };
            let text = match r {
                Some(Revoked::Done) => format!("{who} ya no es administrador."),
                Some(Revoked::InConfig) => {
                    format!("{who} es administrador por configuración: quítalo por SSH.")
                }
                _ => format!("{who} no es administrador."),
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
/// from an admin client is a command. The password (`acceder …`) is accepted
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
            Command::Login(_) if admin => "Ya eres administrador.".to_owned(),
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
                            &format!("@{alias} es administrador (contraseña por radio, cifrada)."),
                            None,
                        );
                        "Eres administrador.".to_owned()
                    }
                    (false, true) => {
                        out.actions = router.alert_admins(
                            &format!(
                                "⚠️ @{alias} envió la contraseña de administración SIN CIFRAR por radio. Cámbiala por SSH."
                            ),
                            None,
                        );
                        "CONTRASEÑA ENVIADA EN CLARO: no vale. Cámbiala por SSH.".to_owned()
                    }
                    (false, false) => "Solo con cifrado. Contraseña incorrecta.".to_owned(),
                    (true, false) => "Contraseña incorrecta.".to_owned(),
                }
            }
            _ if !admin => "No eres administrador.".to_owned(),
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
        assert_eq!(parse("Estado", sig), Some(Command::Status));
        assert_eq!(parse("/admin", sig), Some(Command::Help));
        assert_eq!(parse("/ADMIN status", sig), Some(Command::Status));
        assert_eq!(parse("/admin qué tal", sig), Some(Command::Help));
        assert_eq!(parse("/administrar", sig), None);
        assert_eq!(
            parse("/admin aceptar @bea", sig),
            Some(Command::Accept("bea".into()))
        );
        assert_eq!(parse("aceptar", sig), Some(Command::Help));
        assert_eq!(
            parse("permitir +34 600 123 456", None),
            Some(Command::Help),
            "split number: one argument only"
        );
        assert_eq!(
            parse("permitir +34600123456", sig),
            Some(Command::Allow(Address::SignalPhone("34600123456".into())))
        );
        assert_eq!(
            parse("/admin allow whatsapp +34600123456", None),
            Some(Command::Allow(Address::WhatsappPhone("34600123456".into())))
        );
        assert_eq!(
            parse("bloquear @ana", None),
            Some(Command::Block(Target::Alias("ana".into())))
        );
        assert_eq!(
            parse("bloquear mama.01", sig),
            Some(Command::Block(Target::Contact(Address::SignalUsername(
                "mama.01".into()
            ))))
        );
        assert_eq!(
            parse("permitir +34600123456", None),
            Some(Command::Help),
            "radio admins name the network"
        );
        assert_eq!(
            parse("/admin acceder  caballo pila grapa ", sig),
            Some(Command::Login("caballo pila grapa".into()))
        );
        assert_eq!(parse("/admin login", sig), Some(Command::Help));
        assert_eq!(
            parse("/admin revocar @bea", sig),
            Some(Command::Revoke(Target::Alias("bea".into())))
        );
    }
}
