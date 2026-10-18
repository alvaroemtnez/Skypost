//! Group key, per-sender hash ratchets, encrypt-then-MAC
//! (see `docs/spec/crypto.md`).
//!
//! * Provisioning: a 32-byte group key from a QR code. [`Session::provision`]
//!   derives every sender chain plus the ACK key and then drops the group key,
//!   so a stolen phone cannot decrypt traffic from before the theft.
//! * Each sender id has its own chain: `mk = HMAC(ck, 0x01)`,
//!   `ck' = HMAC(ck, 0x02)`. One message key per counter value, used once.
//! * Message keys expand (HKDF) into a ChaCha20 key and an HMAC-SHA256 key.
//!   The tag is HMAC over header + body, truncated to `tag_len` bytes.
//! * Signed-plaintext mode (legal default on air) uses the same keys and tag,
//!   but leaves the body unencrypted.

use crate::Error;
use crate::header::{HEADER_LEN, Header, MAX_MEMBER_ID};
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use hmac::{Hmac, KeyInit, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::collections::{BTreeMap, VecDeque};
use zeroize::{Zeroize, ZeroizeOnDrop};

type HmacSha256 = Hmac<Sha256>;

pub const KEY_LEN: usize = 32;
const SALT: &[u8] = b"keryx-v0";
/// How far ahead of the expected counter a receiver will ratchet.
pub const MAX_SKIP: u32 = 512;
/// Message keys kept for out-of-order delivery, per sender.
pub const MAX_STORED_SKIPPED: usize = 64;
/// Recently delivered frames remembered for duplicate detection.
const RECENT_LEN: usize = 32;

/// Allowed tag lengths. 8 is the default; 16 is the conservative option.
pub const TAG_LENS: [usize; 2] = [8, 16];

#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct GroupKey(pub [u8; KEY_LEN]);

fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = <HmacSha256 as KeyInit>::new_from_slice(key).expect("HMAC takes any key size");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().into()
}

fn hkdf_expand(prk_or_ikm: &[u8], extract: bool, info: &[u8], out: &mut [u8]) {
    let hk = if extract {
        Hkdf::<Sha256>::new(Some(SALT), prk_or_ikm)
    } else {
        Hkdf::<Sha256>::from_prk(prk_or_ikm).expect("32-byte PRK")
    };
    hk.expand(info, out).expect("valid HKDF length");
}

fn chain_init(gk: &GroupKey, src: u8) -> [u8; 32] {
    let mut out = [0u8; 32];
    hkdf_expand(
        &gk.0,
        true,
        &[b"chain".as_slice(), &[src]].concat(),
        &mut out,
    );
    out
}

fn step(ck: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    (hmac(ck, &[&[0x01]]), hmac(ck, &[&[0x02]]))
}

struct MessageKeys {
    enc: [u8; 32],
    mac: [u8; 32],
}

impl Drop for MessageKeys {
    fn drop(&mut self) {
        self.enc.zeroize();
        self.mac.zeroize();
    }
}

fn message_keys(mk: &[u8; 32]) -> MessageKeys {
    let mut okm = [0u8; 64];
    hkdf_expand(mk, false, b"keys", &mut okm);
    let mut k = MessageKeys {
        enc: [0; 32],
        mac: [0; 32],
    };
    k.enc.copy_from_slice(&okm[..32]);
    k.mac.copy_from_slice(&okm[32..]);
    okm.zeroize();
    k
}

fn apply_cipher(enc: &[u8; 32], header: &[u8; HEADER_LEN], body: &mut [u8]) {
    let mut nonce = [0u8; 12];
    nonce[..HEADER_LEN].copy_from_slice(header);
    let mut c = ChaCha20::new(enc.into(), &nonce.into());
    c.apply_keystream(body);
}

fn tag(mac: &[u8; 32], header: &[u8], body: &[u8]) -> [u8; 32] {
    hmac(mac, &[header, body])
}

#[derive(Clone, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
struct SendChain {
    ck: [u8; 32],
    next: u32,
}

#[derive(Clone, Serialize, Deserialize)]
struct RecvChain {
    ck: [u8; 32],
    next: u32,
    skipped: BTreeMap<u16, [u8; 32]>,
}

impl Drop for RecvChain {
    fn drop(&mut self) {
        self.ck.zeroize();
        for mk in self.skipped.values_mut() {
            mk.zeroize();
        }
    }
}

impl RecvChain {
    /// Find the message key for `counter` without touching `self`; returns the
    /// key and the chain state to commit if the tag verifies.
    fn candidate(&self, src: u8, counter: u16) -> Result<([u8; 32], RecvChain), Error> {
        let c = u32::from(counter);
        if c < self.next {
            let mut next_state = self.clone();
            return match next_state.skipped.remove(&counter) {
                Some(mk) => Ok((mk, next_state)),
                None => Err(Error::Replay { src, counter }),
            };
        }
        if c - self.next > MAX_SKIP {
            return Err(Error::CounterJump);
        }
        let mut s = self.clone();
        while s.next < c {
            let (mk, ck) = step(&s.ck);
            s.skipped.insert(s.next as u16, mk);
            s.ck = ck;
            s.next += 1;
        }
        while s.skipped.len() > MAX_STORED_SKIPPED {
            let oldest = *s.skipped.keys().next().unwrap();
            s.skipped.remove(&oldest);
        }
        let (mk, ck) = step(&s.ck);
        s.ck = ck;
        s.next += 1;
        Ok((mk, s))
    }
}

/// One station's crypto state. Serialize it to persist counters and chains;
/// losing it means re-provisioning from the QR code with a fresh group key.
#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    pub me: u8,
    pub tag_len: usize,
    send: SendChain,
    recv: BTreeMap<u8, RecvChain>,
    ack_key: [u8; 32],
    #[serde(default)]
    recent: VecDeque<(u8, u16, Vec<u8>)>,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.ack_key.zeroize();
    }
}

impl Session {
    /// Derive all chains from the group key for station `me`. The group key is
    /// not stored.
    pub fn provision(gk: &GroupKey, me: u8, tag_len: usize) -> Result<Self, Error> {
        if me > MAX_MEMBER_ID {
            return Err(Error::Range("member id must be 0..=14"));
        }
        if !TAG_LENS.contains(&tag_len) {
            return Err(Error::Range("tag length must be 8 or 16"));
        }
        let mut recv = BTreeMap::new();
        for src in 0..=MAX_MEMBER_ID {
            if src != me {
                recv.insert(
                    src,
                    RecvChain {
                        ck: chain_init(gk, src),
                        next: 0,
                        skipped: BTreeMap::new(),
                    },
                );
            }
        }
        let mut ack_key = [0u8; 32];
        hkdf_expand(&gk.0, true, b"ack", &mut ack_key);
        Ok(Self {
            me,
            tag_len,
            send: SendChain {
                ck: chain_init(gk, me),
                next: 0,
            },
            recv,
            ack_key,
            recent: VecDeque::new(),
        })
    }

    pub fn ack_key(&self) -> &[u8; 32] {
        &self.ack_key
    }

    /// Counter the next sealed message will use.
    pub fn next_counter(&self) -> Option<u16> {
        u16::try_from(self.send.next).ok()
    }

    /// Build a wire frame: header + (encrypted or plain) body + tag.
    /// `header.src` and `header.counter` are filled in here.
    pub fn seal(&mut self, mut header: Header, body: &[u8]) -> Result<Vec<u8>, Error> {
        let counter = self.next_counter().ok_or(Error::CounterExhausted)?;
        header.src = self.me;
        header.counter = counter;
        let hb = header.encode()?;
        let (mk, ck) = step(&self.send.ck);
        let keys = message_keys(&mk);
        let mut out = Vec::with_capacity(HEADER_LEN + body.len() + self.tag_len);
        out.extend_from_slice(&hb);
        let mut b = body.to_vec();
        if header.encrypted {
            apply_cipher(&keys.enc, &hb, &mut b);
        }
        let t = tag(&keys.mac, &hb, &b);
        out.extend_from_slice(&b);
        out.extend_from_slice(&t[..self.tag_len]);
        self.send.ck = ck;
        self.send.next += 1;
        Ok(out)
    }

    /// Verify and open a wire frame. State only changes if the tag verifies.
    pub fn open(&mut self, frame: &[u8]) -> Result<(Header, Vec<u8>), Error> {
        if frame.len() < HEADER_LEN + self.tag_len {
            return Err(Error::TooShort);
        }
        let header = Header::decode(frame)?;
        if header.src == self.me {
            return Err(Error::FromSelf);
        }
        let split = frame.len() - self.tag_len;
        let (hb, rest) = frame[..split].split_at(HEADER_LEN);
        let rx_tag = &frame[split..];
        let chain = self
            .recv
            .get(&header.src)
            .ok_or(Error::Range("unknown sender id"))?;
        let (mk, new_state) = match chain.candidate(header.src, header.counter) {
            Ok(c) => c,
            Err(Error::Replay { src, counter }) => {
                let dup = self
                    .recent
                    .iter()
                    .any(|(s, c, t)| *s == src && *c == counter && t.as_slice() == rx_tag);
                return Err(if dup {
                    Error::Duplicate { src, counter }
                } else {
                    Error::Replay { src, counter }
                });
            }
            Err(e) => return Err(e),
        };
        let keys = message_keys(&mk);
        let expected = tag(&keys.mac, hb, rest);
        let ok: bool = subtle_eq(&expected[..self.tag_len], rx_tag);
        if !ok {
            return Err(Error::AuthFailed);
        }
        let mut body = rest.to_vec();
        if header.encrypted {
            let hb: [u8; HEADER_LEN] = hb.try_into().unwrap();
            apply_cipher(&keys.enc, &hb, &mut body);
        }
        self.recv.insert(header.src, new_state);
        self.recent
            .push_back((header.src, header.counter, rx_tag.to_vec()));
        while self.recent.len() > RECENT_LEN {
            self.recent.pop_front();
        }
        Ok((header, body))
    }
}

/// Constant-time comparison of two equal-length byte slices.
pub(crate) fn subtle_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Truncated HMAC used for compact ACK frames.
pub(crate) fn ack_tag(ack_key: &[u8; 32], data: &[u8]) -> [u8; 32] {
    hmac(ack_key, &[data])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::header::{BROADCAST, MsgType};

    fn pair(tag_len: usize) -> (Session, Session) {
        let gk = GroupKey([7u8; 32]);
        (
            Session::provision(&gk, 1, tag_len).unwrap(),
            Session::provision(&gk, 0, tag_len).unwrap(),
        )
    }

    fn hdr(encrypted: bool) -> Header {
        Header {
            encrypted,
            msg_type: MsgType::Text,
            src: 0,
            dst: BROADCAST,
            counter: 0,
        }
    }

    #[test]
    fn encrypted_roundtrip_and_ciphertext_differs() {
        let (mut a, mut b) = pair(8);
        let f = a.seal(hdr(true), b"hola").unwrap();
        assert_eq!(f.len(), 4 + 4 + 8);
        assert_ne!(&f[4..8], b"hola");
        let (h, body) = b.open(&f).unwrap();
        assert_eq!(body, b"hola");
        assert_eq!((h.src, h.counter), (1, 0));
    }

    #[test]
    fn signed_plaintext_is_readable_but_authenticated() {
        let (mut a, mut b) = pair(16);
        let mut f = a.seal(hdr(false), b"hola").unwrap();
        assert_eq!(&f[4..8], b"hola");
        f[4] ^= 1;
        assert_eq!(b.open(&f), Err(Error::AuthFailed));
    }

    #[test]
    fn out_of_order_replay_and_duplicate() {
        let (mut a, mut b) = pair(8);
        let f0 = a.seal(hdr(true), b"0").unwrap();
        let f1 = a.seal(hdr(true), b"1").unwrap();
        let f2 = a.seal(hdr(true), b"2").unwrap();
        assert_eq!(b.open(&f2).unwrap().1, b"2");
        assert_eq!(b.open(&f0).unwrap().1, b"0");
        assert_eq!(b.open(&f1).unwrap().1, b"1");
        assert_eq!(b.open(&f1), Err(Error::Duplicate { src: 1, counter: 1 }));
    }

    #[test]
    fn forged_frame_does_not_advance_state() {
        let (mut a, mut b) = pair(8);
        let good = a.seal(hdr(true), b"x").unwrap();
        let mut forged = good.clone();
        forged[2] = 0x01; // counter 256, tag no longer valid
        forged[3] = 0x00;
        assert_eq!(b.open(&forged), Err(Error::AuthFailed));
        assert!(b.open(&good).is_ok());
    }

    #[test]
    fn own_frames_and_far_jumps_are_rejected() {
        let (mut a, _) = pair(8);
        let mut a2 = a.clone();
        let f = a.seal(hdr(true), b"x").unwrap();
        assert_eq!(a2.open(&f), Err(Error::FromSelf));
        let gk = GroupKey([7u8; 32]);
        let mut b = Session::provision(&gk, 0, 8).unwrap();
        let mut far = f.clone();
        far[2..4].copy_from_slice(&1000u16.to_be_bytes());
        assert_eq!(b.open(&far), Err(Error::CounterJump));
    }
}
