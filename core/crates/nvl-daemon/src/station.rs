//! Base-station logic with no I/O: frames in, effects out.
//!
//! The async shell in `main.rs` feeds received PHY data in and carries out the
//! returned effects (transmit, deliver to bridges). Keeping this synchronous
//! makes the whole receive/ACK/poll/store-and-forward flow unit-testable.

use crate::outbox::Outbox;
use nvl_proto::ack::{ACK_LEN, Ack};
use nvl_proto::header::{MsgType, peek_type};
use nvl_proto::message::{self, Message};
use nvl_proto::{BROADCAST, Error, Header, crypto::Session};
use std::collections::HashMap;

/// Reassembled data from the PHY plus what the decoder measured.
#[derive(Clone, Debug)]
pub struct RxFrame {
    pub data: Vec<u8>,
    pub snr_db: i8,
    /// Clock offset of the sender, deciseconds (+ = late).
    pub dt_ds: i8,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Effect {
    /// Wire bytes to segment and send on the PHY.
    Transmit {
        to: u8,
        wire: Vec<u8>,
    },
    /// A message to hand to the bridges (Matrix, MeshCore, ntfy, log).
    Deliver {
        header: Header,
        message: Message,
        snr_db: i8,
    },
    /// A queued message reached its field station.
    Delivered {
        to: u8,
        counter: u16,
    },
    Log(String),
}

pub struct Station {
    session: Session,
    outbox: Outbox,
    /// Reply encrypted to a member iff its last message was encrypted, so
    /// emergency mode on the field side switches the base too.
    peer_encrypts: HashMap<u8, bool>,
}

impl Station {
    pub fn new(session: Session) -> Self {
        Self {
            session,
            outbox: Outbox::default(),
            peer_encrypts: HashMap::new(),
        }
    }

    pub fn me(&self) -> u8 {
        self.session.me
    }

    pub fn session(&self) -> &Session {
        &self.session
    }

    /// Queue a message from the family side (a bridge) for field station `to`.
    /// It is sent when that station polls.
    pub fn queue(&mut self, to: u8, msg: Message) {
        self.outbox.push(to, msg);
    }

    fn ack(&self, to: u8, counter: u16, f: &RxFrame) -> Result<Effect, Error> {
        let a = Ack {
            src: self.me(),
            dst: to,
            acked_counter_lo: counter as u8,
            snr_db: f.snr_db,
            dt_ds: f.dt_ds,
        };
        Ok(Effect::Transmit {
            to,
            wire: a.encode(self.session.ack_key())?.to_vec(),
        })
    }

    fn send_next(&mut self, to: u8) -> Result<Option<Effect>, Error> {
        if self.outbox.in_flight(to).is_some() {
            return Ok(None);
        }
        let Some(msg) = self.outbox.peek(to).cloned() else {
            return Ok(None);
        };
        let encrypt = self.peer_encrypts.get(&to).copied().unwrap_or(false);
        let counter = self.session.next_counter().ok_or(Error::CounterExhausted)?;
        let wire = message::encode(&mut self.session, &msg, to, encrypt)?;
        self.outbox.set_in_flight(to, counter, wire.clone());
        Ok(Some(Effect::Transmit { to, wire }))
    }

    pub fn on_rx(&mut self, f: &RxFrame) -> Vec<Effect> {
        match self.handle(f) {
            Ok(effects) => effects,
            Err(e) => vec![Effect::Log(format!("dropped frame: {e}"))],
        }
    }

    fn handle(&mut self, f: &RxFrame) -> Result<Vec<Effect>, Error> {
        let mut out = Vec::new();
        if peek_type(&f.data)? == MsgType::Ack {
            let a = Ack::decode(&f.data[..ACK_LEN.min(f.data.len())], self.session.ack_key())?;
            if a.dst != self.me() {
                return Ok(out);
            }
            if let Some(counter) = self.outbox.ack(a.src, a.acked_counter_lo) {
                out.push(Effect::Delivered { to: a.src, counter });
                out.extend(self.send_next(a.src)?);
            }
            return Ok(out);
        }
        let d = match message::decode_padded(&mut self.session, &f.data) {
            Ok(d) => d,
            Err(Error::Duplicate { src, counter }) => {
                out.push(Effect::Log(format!("duplicate from {src}, re-ACKing")));
                out.push(self.ack(src, counter, f)?);
                return Ok(out);
            }
            Err(e) => return Err(e),
        };
        let h = d.header;
        if h.dst != self.me() && h.dst != BROADCAST {
            return Ok(out);
        }
        self.peer_encrypts.insert(h.src, h.encrypted);
        out.push(self.ack(h.src, h.counter, f)?);
        if d.message == Message::Poll {
            out.extend(self.send_next(h.src)?);
        } else {
            out.push(Effect::Deliver {
                header: h,
                message: d.message,
                snr_db: f.snr_db,
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nvl_proto::JS8_FRAME_PAYLOAD_BYTES as MTU;
    use nvl_proto::crypto::GroupKey;
    use nvl_proto::segment::{reassemble, segment};

    fn over_air(wire: &[u8]) -> RxFrame {
        RxFrame {
            data: reassemble(&segment(wire, MTU)),
            snr_db: -14,
            dt_ds: 2,
        }
    }

    fn setup() -> (Station, Session) {
        let gk = GroupKey([1u8; 32]);
        (
            Station::new(Session::provision(&gk, 0, 8).unwrap()),
            Session::provision(&gk, 1, 8).unwrap(),
        )
    }

    #[test]
    fn delivers_and_acks_a_field_message() {
        let (mut base, mut field) = setup();
        let wire = message::encode(
            &mut field,
            &Message::from_text("Hemos acampado"),
            BROADCAST,
            false,
        )
        .unwrap();
        let fx = base.on_rx(&over_air(&wire));
        let Effect::Transmit { to: 1, wire: ack } = &fx[0] else {
            panic!("{fx:?}")
        };
        let a = Ack::decode(ack, field.ack_key()).unwrap();
        assert_eq!((a.acked_counter_lo, a.snr_db, a.dt_ds), (0, -14, 2));
        assert!(matches!(
            &fx[1],
            Effect::Deliver {
                message: Message::Canned(7),
                ..
            }
        ));
        // The same frame again: re-ACK, no second delivery.
        let fx = base.on_rx(&over_air(&wire));
        assert!(fx.iter().all(|e| !matches!(e, Effect::Deliver { .. })));
        assert!(fx.iter().any(|e| matches!(e, Effect::Transmit { .. })));
    }

    #[test]
    fn poll_releases_queue_in_order_and_mirrors_encryption() {
        let (mut base, mut field) = setup();
        base.queue(1, Message::from_text("Buenas noches"));
        base.queue(1, Message::Text("Avisad al llegar".into()));
        let poll = message::encode(&mut field, &Message::Poll, 0, true).unwrap();
        let fx = base.on_rx(&over_air(&poll));
        let Effect::Transmit { wire, .. } = &fx[1] else {
            panic!("{fx:?}")
        };
        let d = message::decode(&mut field, wire).unwrap();
        assert_eq!(d.message, Message::Canned(16));
        assert!(
            d.header.encrypted,
            "base mirrors the field's emergency mode"
        );

        let ack = Ack {
            src: 1,
            dst: 0,
            acked_counter_lo: d.header.counter as u8,
            snr_db: -20,
            dt_ds: 0,
        };
        let fx = base.on_rx(&over_air(&ack.encode(field.ack_key()).unwrap()));
        assert!(matches!(fx[0], Effect::Delivered { to: 1, .. }));
        let Effect::Transmit { wire, .. } = &fx[1] else {
            panic!("{fx:?}")
        };
        assert_eq!(
            message::decode(&mut field, wire).unwrap().message,
            Message::Text("Avisad al llegar".into())
        );
    }
}
