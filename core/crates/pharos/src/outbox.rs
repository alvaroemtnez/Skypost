//! Store-and-forward queue: messages for each field user wait here until it
//! listens. One message in flight per user; the in-flight frame keeps its
//! counter across retries.

use keryx::{Message, PublicKey};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Queued {
    pub message: Message,
    /// Unix time it was queued.
    pub queued_at: u64,
    /// Caller's reference (e.g. the bridged message to react to on delivery).
    pub token: Option<u64>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Outbox {
    queues: BTreeMap<PublicKey, VecDeque<Queued>>,
    /// Sealed frame of the queue head being sent: (counter, wire).
    #[serde(skip)]
    in_flight: BTreeMap<PublicKey, (u16, Vec<u8>)>,
}

impl Outbox {
    pub fn push(&mut self, to: PublicKey, q: Queued) {
        self.queues.entry(to).or_default().push_back(q);
    }

    pub fn peek(&self, to: &PublicKey) -> Option<&Queued> {
        self.queues.get(to)?.front()
    }

    pub fn len(&self, to: &PublicKey) -> usize {
        self.queues.get(to).map_or(0, VecDeque::len)
    }

    pub fn in_flight(&self, to: &PublicKey) -> Option<&(u16, Vec<u8>)> {
        self.in_flight.get(to)
    }

    pub fn all_in_flight(&self) -> impl Iterator<Item = (&PublicKey, u16)> {
        self.in_flight.iter().map(|(k, (c, _))| (k, *c))
    }

    pub fn set_in_flight(&mut self, to: PublicKey, counter: u16, wire: Vec<u8>) {
        self.in_flight.insert(to, (counter, wire));
    }

    /// Give up on the current attempt; the message stays at the head.
    pub fn abandon(&mut self, to: &PublicKey) {
        self.in_flight.remove(to);
    }

    /// The in-flight message was ACKed: drop it from the queue.
    pub fn delivered(&mut self, to: &PublicKey, counter: u16) -> Option<Queued> {
        if self.in_flight.get(to)?.0 != counter {
            return None;
        }
        self.in_flight.remove(to);
        self.queues.get_mut(to)?.pop_front()
    }

    /// Remove messages queued before `cutoff` that are not in flight.
    pub fn expire(&mut self, cutoff: u64) -> Vec<(PublicKey, Queued)> {
        let mut out = Vec::new();
        for (k, q) in &mut self.queues {
            let keep_head = self.in_flight.contains_key(k);
            let mut kept = VecDeque::new();
            for (i, item) in q.drain(..).enumerate() {
                if item.queued_at < cutoff && !(keep_head && i == 0) {
                    out.push((*k, item));
                } else {
                    kept.push_back(item);
                }
            }
            *q = kept;
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn q(at: u64) -> Queued {
        Queued {
            message: Message::Canned(0),
            queued_at: at,
            token: Some(at),
        }
    }

    #[test]
    fn delivered_pops_only_the_matching_counter() {
        let k = PublicKey([1; 32]);
        let mut o = Outbox::default();
        o.push(k, q(1));
        o.push(k, q(2));
        o.set_in_flight(k, 300, vec![]);
        assert_eq!(o.delivered(&k, 44), None);
        assert_eq!(o.delivered(&k, 300).unwrap().token, Some(1));
        assert_eq!(o.len(&k), 1);
    }

    #[test]
    fn expiry_spares_the_message_in_flight() {
        let k = PublicKey([1; 32]);
        let mut o = Outbox::default();
        o.push(k, q(1));
        o.push(k, q(2));
        o.push(k, q(9));
        o.set_in_flight(k, 0, vec![]);
        let gone = o.expire(5);
        assert_eq!(gone.len(), 1);
        assert_eq!(gone[0].1.token, Some(2));
        assert_eq!(o.len(&k), 2);
    }
}
