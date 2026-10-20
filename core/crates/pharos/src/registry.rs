//! Field users known to this Pharos: alias, registration status, listen
//! window, mirrored mode and bridge handles (see `docs/spec/pharos.md`).

use keryx::PublicKey;
use keryx::bridge::{Address, PHAROS_HANDLE_BASE};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Registered,
    /// Waiting for the operator (closed Pharos).
    Pending,
    /// Registration refused by the operator.
    Refused,
    /// Tried to register but failed (alias taken, wrong code); may retry.
    Unregistered,
    /// Ignored entirely, not even ACKed.
    Blocked,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Client {
    pub alias: String,
    pub status: Status,
    /// Got the emergency-mode "encrypted only" notice already.
    #[serde(default)]
    pub told_encrypted_only: bool,
    /// Unix time until which the client said it keeps listening.
    #[serde(default)]
    pub listen_until: u64,
    /// Unix time of the last authenticated frame from it.
    #[serde(default)]
    pub last_heard: u64,
    /// Mirrored mode: encrypt to it iff its last message was encrypted.
    #[serde(default)]
    pub encrypts: bool,
    /// Bridge handles in use with this client.
    #[serde(default)]
    pub handles: BTreeMap<u8, Address>,
    #[serde(default)]
    next_handle: u8,
}

impl Client {
    pub fn new(alias: &str, status: Status) -> Self {
        Self {
            alias: alias.to_owned(),
            status,
            told_encrypted_only: false,
            listen_until: 0,
            last_heard: 0,
            encrypts: false,
            handles: BTreeMap::new(),
            next_handle: PHAROS_HANDLE_BASE,
        }
    }

    pub fn is_listening(&self, now: u64) -> bool {
        now < self.listen_until
    }

    pub fn handle_of(&self, address: &Address) -> Option<u8> {
        self.handles
            .iter()
            .find(|(_, a)| *a == address)
            .map(|(h, _)| *h)
    }

    /// A Pharos-side handle (128..=255) for `address`: the existing one, or the
    /// next one round-robin (rebinding the oldest when all are used).
    /// Returns the handle and whether it is new (needs a `BridgeOpen`).
    pub fn ensure_handle(&mut self, address: &Address) -> (u8, bool) {
        if let Some(h) = self.handle_of(address) {
            return (h, false);
        }
        let h = self.next_handle.max(PHAROS_HANDLE_BASE);
        self.next_handle = h.checked_add(1).unwrap_or(PHAROS_HANDLE_BASE);
        self.handles.insert(h, address.clone());
        (h, true)
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Registry {
    clients: BTreeMap<PublicKey, Client>,
}

impl Registry {
    pub fn get(&self, key: &PublicKey) -> Option<&Client> {
        self.clients.get(key)
    }

    pub fn get_mut(&mut self, key: &PublicKey) -> Option<&mut Client> {
        self.clients.get_mut(key)
    }

    pub fn insert(&mut self, key: PublicKey, client: Client) {
        self.clients.insert(key, client);
    }

    pub fn iter(&self) -> impl Iterator<Item = (&PublicKey, &Client)> {
        self.clients.iter()
    }

    /// A registered client by alias, case-insensitively, `@` optional.
    pub fn by_alias(&self, alias: &str) -> Option<PublicKey> {
        let alias = alias.trim_start_matches('@');
        self.clients
            .iter()
            .find(|(_, c)| {
                c.status == Status::Registered && c.alias.to_lowercase() == alias.to_lowercase()
            })
            .map(|(k, _)| *k)
    }

    /// Any client with this alias, whatever its status (admin commands).
    pub fn find(&self, alias: &str) -> Option<PublicKey> {
        let alias = alias.trim_start_matches('@').to_lowercase();
        self.clients
            .iter()
            .find(|(_, c)| c.alias.to_lowercase() == alias)
            .map(|(k, _)| *k)
    }

    /// Another registered, pending or blocked client already uses this alias.
    pub fn alias_taken(&self, alias: &str, except: &PublicKey) -> bool {
        self.clients.iter().any(|(k, c)| {
            k != except
                && matches!(
                    c.status,
                    Status::Registered | Status::Pending | Status::Blocked
                )
                && c.alias.to_lowercase() == alias.to_lowercase()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handles_are_reused_then_round_robin() {
        let mut c = Client::new("ana", Status::Registered);
        let a = Address::SignalPhone("34600000001".into());
        let b = Address::SignalPhone("34600000002".into());
        assert_eq!(c.ensure_handle(&a), (128, true));
        assert_eq!(c.ensure_handle(&a), (128, false));
        assert_eq!(c.ensure_handle(&b), (129, true));
        c.next_handle = 255;
        assert_eq!(c.ensure_handle(&Address::SignalPhone("3".into())).0, 255);
        assert_eq!(c.ensure_handle(&Address::SignalPhone("4".into())).0, 128);
        assert_eq!(c.handle_of(&a), None, "128 was rebound");
    }

    #[test]
    fn alias_lookup_ignores_case_at_sign_and_unregistered() {
        let mut r = Registry::default();
        let k1 = PublicKey([1; 32]);
        let k2 = PublicKey([2; 32]);
        r.insert(k1, Client::new("Mamá", Status::Registered));
        r.insert(k2, Client::new("pepe", Status::Pending));
        assert_eq!(r.by_alias("@mamá"), Some(k1));
        assert_eq!(r.by_alias("pepe"), None);
        assert!(r.alias_taken("PEPE", &k1));
        assert!(!r.alias_taken("pepe", &k2));
    }
}
