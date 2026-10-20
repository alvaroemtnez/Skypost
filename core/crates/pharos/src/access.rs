//! Persisted access state (decision 0021): the switches, the admins, pending
//! access requests and the hourly request notices to admins. Lives in the
//! station state file, so on-air changes survive restarts; `base.toml` only
//! seeds it on the first run.

use keryx::PublicKey;
use keryx::admin::{Switch, Switches, network_bit, who};
use keryx::bridge::Address;
use serde::{Deserialize, Serialize};

/// A field user (by key) or a bridged contact (by address).
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Who {
    Radio(PublicKey),
    Contact(Address),
}

impl Who {
    /// [`keryx::admin::who`] code.
    pub fn network(&self) -> u8 {
        match self {
            Self::Radio(_) => who::RADIO,
            Self::Contact(a) => who::of(a),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdminRecord {
    pub who: Who,
    /// [`keryx::admin::how`].
    pub how: u8,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Request {
    pub num: u8,
    pub who: Who,
    /// Alias, profile name or number, or MeshCore name and key prefix.
    pub name: String,
    pub at: u64,
}

/// Per admin: when the last "new requests" notice went, and the networks
/// with requests since then (sent when the hour is up).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
struct Notice {
    who: Who,
    last: u64,
    waiting: u8,
}

/// One notice per admin per hour at most.
pub const NOTICE_EVERY_SECS: u64 = 3600;
pub const MAX_REQUESTS: usize = 20;
pub const DEFAULT_REQUEST_TTL_SECS: u64 = 72 * 3600;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AccessState {
    pub switches: Switches,
    /// How long a request stays pending (`pharos set request-ttl-hours`).
    pub request_ttl_secs: u64,
    pub admins: Vec<AdminRecord>,
    pub requests: Vec<Request>,
    next_request: u8,
    notices: Vec<Notice>,
}

impl Default for AccessState {
    /// Closed, taking requests, password login on: a new Pharos is private
    /// but reachable.
    fn default() -> Self {
        let mut switches = Switches::default();
        switches.set(Switch::Requests, true);
        switches.set(Switch::Login, true);
        Self {
            switches,
            request_ttl_secs: DEFAULT_REQUEST_TTL_SECS,
            admins: Vec::new(),
            requests: Vec::new(),
            next_request: 0,
            notices: Vec::new(),
        }
    }
}

impl AccessState {
    pub fn on(&self, s: Switch) -> bool {
        self.switches.get(s)
    }

    pub fn is_admin(&self, w: &Who) -> bool {
        self.admins.iter().any(|a| a.who == *w)
    }

    /// False if it was an admin already.
    pub fn add_admin(&mut self, w: Who, how: u8) -> bool {
        if self.is_admin(&w) {
            return false;
        }
        self.admins.push(AdminRecord { who: w, how });
        true
    }

    /// `keep_one`: refuse to remove the last admin (password login off).
    /// `Err(true)` = it would be the last one, `Err(false)` = not an admin.
    pub fn remove_admin(&mut self, w: &Who, keep_one: bool) -> Result<(), bool> {
        if !self.is_admin(w) {
            return Err(false);
        }
        if keep_one && self.admins.len() == 1 {
            return Err(true);
        }
        self.admins.retain(|a| a.who != *w);
        self.notices.retain(|n| n.who != *w);
        Ok(())
    }

    pub fn request_of(&self, w: &Who) -> Option<&Request> {
        self.requests.iter().find(|r| r.who == *w)
    }

    /// A new pending request; `None` when the list is full. Numbers count up
    /// and wrap, skipping those in use, so a stale number does not point at a
    /// newcomer while its request is pending. Notes it for the admin notices.
    pub fn add_request(&mut self, w: Who, name: String, now: u64) -> Option<u8> {
        if let Some(r) = self.request_of(&w) {
            return Some(r.num);
        }
        if self.requests.len() >= MAX_REQUESTS {
            return None;
        }
        let mut num = self.next_request;
        while self.requests.iter().any(|r| r.num == num) {
            num = num.wrapping_add(1);
        }
        self.next_request = num.wrapping_add(1);
        let bit = network_bit(w.network());
        self.requests.push(Request {
            num,
            who: w,
            name,
            at: now,
        });
        for a in &self.admins {
            match self.notices.iter_mut().find(|n| n.who == a.who) {
                Some(n) => n.waiting |= bit,
                None => self.notices.push(Notice {
                    who: a.who.clone(),
                    last: 0,
                    waiting: bit,
                }),
            }
        }
        Some(num)
    }

    pub fn take_request(&mut self, num: u8) -> Option<Request> {
        let i = self.requests.iter().position(|r| r.num == num)?;
        Some(self.requests.remove(i))
    }

    pub fn drop_request(&mut self, w: &Who) -> Option<Request> {
        let i = self.requests.iter().position(|r| r.who == *w)?;
        Some(self.requests.remove(i))
    }

    /// Requests older than the TTL, removed.
    pub fn expire(&mut self, now: u64) -> Vec<Request> {
        let ttl = self.request_ttl_secs;
        let (old, keep) = std::mem::take(&mut self.requests)
            .into_iter()
            .partition(|r| now.saturating_sub(r.at) >= ttl);
        self.requests = keep;
        old
    }

    /// Admins owed a "new requests" notice now: (admin, networks mask).
    /// Marks them sent. Empty in stealth.
    pub fn due_notices(&mut self, now: u64) -> Vec<(Who, u8)> {
        if self.on(Switch::Stealth) {
            return Vec::new();
        }
        let mut out = Vec::new();
        for n in &mut self.notices {
            if n.waiting != 0 && now.saturating_sub(n.last) >= NOTICE_EVERY_SECS {
                out.push((n.who.clone(), n.waiting));
                n.waiting = 0;
                n.last = now;
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use keryx::admin::how;

    const T: u64 = 1_800_000_000;

    fn mama() -> Who {
        Who::Contact(Address::WhatsappPhone("34611111111".into()))
    }

    #[test]
    fn requests_are_numbered_capped_and_expire() {
        let mut a = AccessState::default();
        assert_eq!(a.add_request(mama(), "Mamá".into(), T), Some(0));
        assert_eq!(
            a.add_request(mama(), "Mamá".into(), T + 1),
            Some(0),
            "one per person"
        );
        for i in 1..MAX_REQUESTS as u8 {
            a.add_request(Who::Radio(PublicKey([i; 32])), format!("u{i}"), T);
        }
        assert_eq!(
            a.add_request(Who::Radio(PublicKey([99; 32])), "x".into(), T),
            None
        );
        assert_eq!(a.take_request(0).unwrap().name, "Mamá");
        assert_eq!(
            a.add_request(Who::Radio(PublicKey([98; 32])), "y".into(), T),
            Some(20),
            "numbers keep counting up"
        );
        a.request_ttl_secs = 10;
        assert_eq!(a.expire(T + 9).len(), 0);
        assert_eq!(a.expire(T + 10).len(), 20);
    }

    #[test]
    fn admins_and_the_last_one() {
        let mut a = AccessState::default();
        let ana = Who::Radio(PublicKey([1; 32]));
        assert!(a.add_admin(ana.clone(), how::SSH));
        assert!(!a.add_admin(ana.clone(), how::BY_ADMIN));
        assert_eq!(a.remove_admin(&ana, true), Err(true));
        assert_eq!(a.remove_admin(&mama(), true), Err(false));
        assert_eq!(a.remove_admin(&ana, false), Ok(()));
    }

    #[test]
    fn notices_are_hourly_merged_and_silent_in_stealth() {
        let mut a = AccessState::default();
        let ana = Who::Radio(PublicKey([1; 32]));
        a.add_admin(ana.clone(), how::SSH);
        a.add_request(mama(), "Mamá".into(), T);
        assert_eq!(
            a.due_notices(T),
            vec![(ana.clone(), network_bit(who::WHATSAPP))]
        );
        a.add_request(Who::Radio(PublicKey([2; 32])), "bea".into(), T + 60);
        a.add_request(
            Who::Contact(Address::MeshcoreContact([3; 32])),
            "R".into(),
            T + 70,
        );
        assert!(a.due_notices(T + 100).is_empty(), "not within the hour");
        assert_eq!(
            a.due_notices(T + 3600),
            vec![(ana, network_bit(who::RADIO) | network_bit(who::MESHCORE))]
        );
        a.add_request(Who::Radio(PublicKey([4; 32])), "c".into(), T + 4000);
        a.switches.set(Switch::Stealth, true);
        assert!(a.due_notices(T + 9000).is_empty());
    }
}
