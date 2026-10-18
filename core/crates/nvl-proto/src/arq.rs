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
}

impl Default for ArqConfig {
    fn default() -> Self {
        Self {
            max_attempts: 4,
            guard_secs: 5,
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
    last_snr_db: Option<f32>,
}

impl ArqSender {
    pub fn new(cfg: ArqConfig, policy: LanePolicy) -> Self {
        Self {
            cfg,
            policy,
            pending: None,
            last_snr_db: None,
        }
    }

    pub fn is_busy(&self) -> bool {
        self.pending.is_some()
    }

    /// SNR the other end last reported, used to choose the next lane.
    pub fn last_snr_db(&self) -> Option<f32> {
        self.last_snr_db
    }

    fn deadline(&self, now: u64, frames: u32, lane: Lane) -> u64 {
        // Our frames, then one frame slot for the ACK, then guard time.
        now + u64::from((frames + 1) * lane.frame_secs() + self.cfg.guard_secs)
    }

    /// Start sending a message of `frames` PHY frames.
    pub fn send(&mut self, counter: u16, frames: u32, now: u64) -> Result<ArqAction, Error> {
        if self.pending.is_some() {
            return Err(Error::Busy);
        }
        let lane = choose(self.last_snr_db, &self.policy);
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

    /// An authenticated ACK arrived for this station.
    pub fn on_ack(&mut self, acked_counter_lo: u8, snr_db: i8, dt_ds: i8) -> Option<ArqAction> {
        let p = self.pending?;
        if p.counter as u8 != acked_counter_lo {
            return None;
        }
        self.pending = None;
        self.last_snr_db = Some(f32::from(snr_db));
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
        assert_eq!(s.on_ack(99, -10, 0), None);
        assert_eq!(
            s.on_ack(7, -10, 3),
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
    }

    #[test]
    fn steps_down_then_fails() {
        let cfg = ArqConfig {
            max_attempts: 3,
            guard_secs: 5,
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
