//! Store-and-forward queue: messages from the family side wait here until
//! their field station polls. One message in flight per station.
//!
//! TODO(phase 1): persist to disk (SQLite or a JSON file) so a reboot of the
//! Pi does not lose queued messages.

use nvl_proto::Message;
use std::collections::{HashMap, VecDeque};

#[derive(Default)]
pub struct Outbox {
    queues: HashMap<u8, VecDeque<Message>>,
    in_flight: HashMap<u8, (u16, Vec<u8>)>,
}

impl Outbox {
    pub fn push(&mut self, to: u8, msg: Message) {
        self.queues.entry(to).or_default().push_back(msg);
    }

    pub fn peek(&self, to: u8) -> Option<&Message> {
        self.queues.get(&to)?.front()
    }

    pub fn len(&self, to: u8) -> usize {
        self.queues.get(&to).map_or(0, VecDeque::len)
    }

    pub fn in_flight(&self, to: u8) -> Option<&(u16, Vec<u8>)> {
        self.in_flight.get(&to)
    }

    pub fn set_in_flight(&mut self, to: u8, counter: u16, wire: Vec<u8>) {
        self.in_flight.insert(to, (counter, wire));
    }

    /// Match an ACK (low counter byte) against the in-flight message for `from`.
    /// On a match, drop that message from the queue and return its counter.
    pub fn ack(&mut self, from: u8, counter_lo: u8) -> Option<u16> {
        let counter = self.in_flight.get(&from)?.0;
        if counter as u8 != counter_lo {
            return None;
        }
        self.in_flight.remove(&from);
        self.queues.get_mut(&from)?.pop_front();
        Some(counter)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ack_pops_only_the_matching_message() {
        let mut o = Outbox::default();
        o.push(1, Message::Poll);
        o.push(1, Message::Canned(0));
        o.set_in_flight(1, 300, vec![1, 2, 3]);
        assert_eq!(o.ack(1, 0), None);
        assert_eq!(o.ack(1, 300u16 as u8), Some(300));
        assert_eq!(o.len(1), 1);
        assert_eq!(o.peek(1), Some(&Message::Canned(0)));
    }
}
