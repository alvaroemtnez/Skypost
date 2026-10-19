//! Stop-and-wait ARQ for the sending side, as a pure state machine.
//! The caller owns the clock and the radio; this only decides what to do.
//! See `docs/spec/arq-and-lanes.md`.

use crate::Error;
use crate::lane::{Lane, LanePolicy, choose};

#[derive(Clone, Copy, Debug)]
pub struct ArqConfig {
    /// Total transmissions per message, first try included.
    pub max_attempts: u8,
    /// Seconds added to every wait for PTT, decoding and processing delays.
    pub guard_secs: u32,
    /// SNR reports older than this are ignored when choosing a lane.
    pub snr_max_age_secs: u64,
    /// Extra frame slots to wait for an ACK: 0 for one peer, 2 for groups
    /// (members ACK after a random 0–2 slot delay).
    pub extra_ack_slots: u32,
}

impl Default for ArqConfig {
    fn default() -> Self {
        Self {
            max_attempts: 4,
            guard_secs: 5,
            snr_max_age_secs: 30 * 60,
            extra_ack_slots: 0,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ArqAction {
    /// Send the message's PHY frames on `lane`.
    Transmit {
        counter: u16,
        lane: Lane,
        attempt: u8,
    },
    Delivered {
        counter: u16,
        snr_db: i8,
        dt_ds: i8,
    },
    Failed {
        counter: u16,
    },
}

#[derive(Clone, Copy, Debug)]
struct Pending {
    counter: u16,
    frames: u32,
    attempt: u8,
    lane: Lane,
    deadline: u64,
}

pub struct ArqSender {
    cfg: ArqConfig,
    policy: LanePolicy,
    pending: Option<Pending>,
    /// Last SNR the other end reported, and when.
    last_snr: Option<(f32, u64)>,
}

impl ArqSender {
    pub fn new(cfg: ArqConfig, policy: LanePolicy) -> Self {
        Self {
            cfg,
            policy,
            pending: None,
            last_snr: None,
        }
    }

    pub fn is_busy(&self) -> bool {
        self.pending.is_some()
    }

    /// SNR the other end last reported, if still fresh at `now`.
    pub fn last_snr_db(&self, now: u64) -> Option<f32> {
        self.last_snr
            .filter(|(_, at)| now.saturating_sub(*at) <= self.cfg.snr_max_age_secs)
            .map(|(snr, _)| snr)
    }

    fn deadline(&self, now: u64, frames: u32, lane: Lane) -> u64 {
        // Our frames, then the ACK slot(s), then guard time.
        let slots = frames + 1 + self.cfg.extra_ack_slots;
        now + u64::from(slots * lane.frame_secs() + self.cfg.guard_secs)
    }

    /// Start sending a message of `frames` PHY frames.
    pub fn send(&mut self, counter: u16, frames: u32, now: u64) -> Result<ArqAction, Error> {
        self.send_on(
            counter,
            frames,
            now,
            choose(self.last_snr_db(now), &self.policy),
        )
    }

    /// Like [`Self::send`] on a fixed lane (discovery and pings use Js8Slow).
    pub fn send_on(
        &mut self,
        counter: u16,
        frames: u32,
        now: u64,
        lane: Lane,
    ) -> Result<ArqAction, Error> {
        if self.pending.is_some() {
            return Err(Error::Busy);
        }
        self.pending = Some(Pending {
            counter,
            frames,
            attempt: 1,
            lane,
            deadline: self.deadline(now, frames, lane),
        });
        Ok(ArqAction::Transmit {
            counter,
            lane,
            attempt: 1,
        })
    }

    /// Counter of the message waiting for its ACK.
    pub fn pending_counter(&self) -> Option<u16> {
        self.pending.map(|p| p.counter)
    }

    /// An authenticated ACK (see `Keyring::open_ack`) arrived at `now`.
    pub fn on_ack(&mut self, counter: u16, snr_db: i8, dt_ds: i8, now: u64) -> Option<ArqAction> {
        let p = self.pending?;
        if p.counter != counter {
            return None;
        }
        self.pending = None;
        self.last_snr = Some((f32::from(snr_db), now));
        Some(ArqAction::Delivered {
            counter: p.counter,
            snr_db,
            dt_ds,
        })
    }

    /// Call periodically. Retries on a more robust lane after each timeout.
    pub fn on_tick(&mut self, now: u64) -> Option<ArqAction> {
        let mut p = self.pending?;
        if now < p.deadline {
            return None;
        }
        if p.attempt >= self.cfg.max_attempts {
            self.pending = None;
            return Some(ArqAction::Failed { counter: p.counter });
        }
        p.attempt += 1;
        p.lane = p.lane.more_robust().unwrap_or(p.lane);
        p.deadline = self.deadline(now, p.frames, p.lane);
        self.pending = Some(p);
        Some(ArqAction::Transmit {
            counter: p.counter,
            lane: p.lane,
            attempt: p.attempt,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delivers_on_ack_and_remembers_snr() {
        let mut s = ArqSender::new(ArqConfig::default(), LanePolicy::default());
        assert_eq!(
            s.send(7, 3, 0).unwrap(),
            ArqAction::Transmit {
                counter: 7,
                lane: Lane::Js8Normal,
                attempt: 1
            }
        );
        assert_eq!(s.send(8, 1, 0), Err(Error::Busy));
        assert_eq!(s.on_ack(99, -10, 0, 50), None);
        assert_eq!(
            s.on_ack(7, -10, 3, 50),
            Some(ArqAction::Delivered {
                counter: 7,
                snr_db: -10,
                dt_ds: 3
            })
        );
        // -10 dB with a 6 dB margin allows Turbo (floor -18).
        assert_eq!(
            s.send(8, 1, 100).unwrap(),
            ArqAction::Transmit {
                counter: 8,
                lane: Lane::Js8Turbo,
                attempt: 1
            }
        );
        s.on_ack(8, -10, 0, 100);
        // Half an hour later the report is stale: back to the initial lane.
        assert_eq!(s.last_snr_db(100 + 1801), None);
        assert!(matches!(
            s.send(9, 1, 100 + 1801).unwrap(),
            ArqAction::Transmit {
                lane: Lane::Js8Normal,
                ..
            }
        ));
    }

    #[test]
    fn steps_down_then_fails() {
        let cfg = ArqConfig {
            max_attempts: 3,
            ..ArqConfig::default()
        };
        let mut s = ArqSender::new(cfg, LanePolicy::default());
        s.send(1, 2, 0).unwrap();
        // Normal: (2 + 1) * 15 + 5 = 50 s.
        assert_eq!(s.on_tick(49), None);
        assert_eq!(
            s.on_tick(50),
            Some(ArqAction::Transmit {
                counter: 1,
                lane: Lane::Js8Slow,
                attempt: 2
            })
        );
        // Slow: (2 + 1) * 30 + 5 = 95 s after the retry.
        assert_eq!(
            s.on_tick(50 + 95),
            Some(ArqAction::Transmit {
                counter: 1,
                lane: Lane::Js8Slow,
                attempt: 3
            })
        );
        assert_eq!(
            s.on_tick(50 + 95 + 95),
            Some(ArqAction::Failed { counter: 1 })
        );
        assert!(!s.is_busy());
    }
}
