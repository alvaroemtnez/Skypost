//! Admin commands, from a bridge admin account (`[bot] admins`) or an admin
//! field user over the radio (`[[clients]] admin = true`). Quick management
//! only; everything else is done over SSH. Commands are Spanish words:
//!
//! ```text
//! ayuda                       list the commands
//! estado                      clients, pending registrations, queued messages
//! clientes                    every field user and its status
//! aceptar @alias              admit a pending registration
//! rechazar @alias             refuse it
//! bloquear @alias | número    ignore a field user or a bridged contact
//! desbloquear @alias | número
//! permitir número             authorise a bridged contact
//! ```
//!
//! A number is a phone with country code (`+34…`, on the admin's own network
//! unless `signal`/`whatsapp` is given first), a Signal username or a
//! MeshCore public key in hex.

use crate::registry::Status;
use crate::router::Router;
use crate::station::{Effect, Station};
use keryx::bridge::{Address, network};

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
    Accept(String),
    Refuse(String),
    Block(Target),
    Unblock(Target),
    Allow(Address),
}

pub const HELP: &str = "Órdenes: estado · clientes · aceptar @alias · rechazar @alias · \
bloquear @alias|número · desbloquear @alias|número · permitir número";

const VERBS: [&str; 8] = [
    "ayuda",
    "estado",
    "clientes",
    "aceptar",
    "rechazar",
    "bloquear",
    "desbloquear",
    "permitir",
];

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

/// `None`: not a command (treat as an ordinary message). A known verb with
/// bad arguments gives `Help`.
pub fn parse(text: &str, net: Option<u8>) -> Option<Command> {
    let words: Vec<&str> = text.split_whitespace().collect();
    let (verb, args) = words.split_first()?;
    let verb = verb.to_lowercase();
    if !VERBS.contains(&verb.as_str()) {
        return None;
    }
    let alias = || match args {
        [a] => Some(a.trim_start_matches('@').to_owned()),
        _ => None,
    };
    let cmd = match verb.as_str() {
        "ayuda" => Some(Command::Help),
        "estado" => Some(Command::Status),
        "clientes" => Some(Command::Clients),
        "aceptar" => alias().map(Command::Accept),
        "rechazar" => alias().map(Command::Refuse),
        "bloquear" => target(args, net).map(Command::Block),
        "desbloquear" => target(args, net).map(Command::Unblock),
        "permitir" => contact(args, net).map(Command::Allow),
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

/// Carry out a command. Returns the reply text and any station effects (for
/// example the notice an approved client gets).
pub fn execute(
    cmd: Command,
    station: &mut Station,
    router: &mut Router,
    now: u64,
) -> (String, Vec<Effect>) {
    let find = |s: &Station, a: &str| s.registry().find(a);
    match cmd {
        Command::Help => (HELP.to_owned(), vec![]),
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
        assert_eq!(
            parse("aceptar @bea", sig),
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
            parse("permitir whatsapp +34600123456", None),
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
    }
}
