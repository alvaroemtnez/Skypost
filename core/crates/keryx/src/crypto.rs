//! Primitives and the v1 key schedule (see `docs/spec/crypto.md`).
//!
//! * Every station has an X25519 [`Identity`]. Its public key is the station's
//!   name on the network; a 4-bit short id derived from it goes on air.
//! * A link is a pair of identities (root from X25519) or a group (root from a
//!   random [`GroupKey`]). Per-message keys come statelessly from
//!   (root, sender, counter); each is used once.
//! * Message keys expand into a ChaCha20 key and an HMAC-SHA256 key. The tag is
//!   HMAC over header + body, truncated to [`TAG_LEN`] bytes.
//!
//! Link bookkeeping (counters, replay windows) lives in [`crate::keyring`].

use crate::Error;
use crate::header::HEADER_LEN;
use argon2::{Algorithm, Argon2, Params, Version};
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};
use data_encoding::BASE32_NOPAD;
use hkdf::Hkdf;
use hmac::{Hmac, KeyInit, Mac};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use sha2::{Digest, Sha256};
use std::fmt;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

type HmacSha256 = Hmac<Sha256>;

pub const KEY_LEN: usize = 32;
/// Tag on every authenticated message frame.
pub const TAG_LEN: usize = 8;
/// Tag on compact frames (ACK, Poll), which must fit one JS8 frame.
pub const COMPACT_TAG_LEN: usize = 4;
const SALT: &[u8] = b"keryx-v1";

pub(crate) fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = <HmacSha256 as KeyInit>::new_from_slice(key).expect("HMAC takes any key size");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().into()
}

pub(crate) fn hkdf_expand(prk: &[u8], info: &[&[u8]], out: &mut [u8]) {
    let hk = Hkdf::<Sha256>::from_prk(prk).expect("32-byte PRK");
    hk.expand_multi_info(info, out).expect("valid HKDF length");
}

fn hkdf_extract(ikm: &[&[u8]]) -> Root {
    let ikm = Zeroizing::new(ikm.concat());
    let (prk, _) = Hkdf::<Sha256>::extract(Some(SALT), &ikm);
    Root(prk.into())
}

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

/// Argon2id with the OWASP minimum (19 MiB, 2 passes, 1 lane): about a
/// second on a low-end phone. Used for backup passphrases and access codes.
pub(crate) fn argon2id(secret: &str, salt: &[u8]) -> Result<Zeroizing<[u8; 32]>, Error> {
    let params =
        Params::new(19 * 1024, 2, 1, Some(32)).map_err(|_| Error::Range("argon2 params"))?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(secret.as_bytes(), salt, out.as_mut())
        .map_err(|_| Error::Range("argon2 input"))?;
    Ok(out)
}

pub(crate) fn sha256_parts(parts: &[&[u8]]) -> [u8; 32] {
    sha256(parts)
}

/// Fill `N` bytes from the operating system's RNG.
pub fn random_bytes<const N: usize>() -> Result<[u8; N], Error> {
    let mut b = [0u8; N];
    getrandom::fill(&mut b).map_err(|_| Error::Random)?;
    Ok(b)
}

/// Constant-time comparison of two equal-length byte slices.
pub(crate) fn subtle_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Short id on air: 0..=14 (15 is broadcast).
pub(crate) fn short_id_of(fingerprint: &[u8; 32]) -> u8 {
    fingerprint[0] % 15
}

macro_rules! base32_newtype {
    ($name:ident) => {
        impl $name {
            pub fn to_base32(&self) -> String {
                BASE32_NOPAD.encode(&self.0)
            }

            pub fn from_base32(s: &str) -> Result<Self, Error> {
                let v = BASE32_NOPAD
                    .decode(s.trim().to_ascii_uppercase().as_bytes())
                    .map_err(|_| Error::Malformed("bad base32"))?;
                Ok(Self(
                    v.try_into()
                        .map_err(|_| Error::Malformed("key must be 32 bytes"))?,
                ))
            }
        }

        impl Serialize for $name {
            fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
                s.serialize_str(&self.to_base32())
            }
        }

        impl<'de> Deserialize<'de> for $name {
            fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
                let s = String::deserialize(d)?;
                Self::from_base32(&s).map_err(serde::de::Error::custom)
            }
        }
    };
}

/// X25519 public key: a station's identity.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PublicKey(pub [u8; KEY_LEN]);
base32_newtype!(PublicKey);

impl PublicKey {
    pub fn fingerprint(&self) -> [u8; 32] {
        sha256(&[b"keryx-v1/id", &self.0])
    }

    pub fn short_id(&self) -> u8 {
        short_id_of(&self.fingerprint())
    }

    /// First 10 fingerprint bytes as "4F2A 9C11 …", for comparing by eye or
    /// over the phone.
    pub fn safety_number(&self) -> String {
        let hex: String = self.fingerprint()[..10]
            .iter()
            .map(|b| format!("{b:02X}"))
            .collect();
        hex.as_bytes()
            .chunks(4)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>()
            .join(" ")
    }
}

impl fmt::Debug for PublicKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "PublicKey({}…)", &self.to_base32()[..8])
    }
}

/// Fingerprint of a group key; how a group is named locally.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct GroupId(pub [u8; 32]);
base32_newtype!(GroupId);

impl GroupId {
    pub fn short_id(&self) -> u8 {
        short_id_of(&self.0)
    }
}

impl fmt::Debug for GroupId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "GroupId({}…)", &self.to_base32()[..8])
    }
}

/// 32 random bytes shared by every group member. Whoever holds it is a member.
#[derive(Clone, PartialEq, Eq, Zeroize, ZeroizeOnDrop)]
pub struct GroupKey(pub [u8; KEY_LEN]);
base32_newtype!(GroupKey);

impl GroupKey {
    pub fn generate() -> Result<Self, Error> {
        Ok(Self(random_bytes()?))
    }

    pub fn id(&self) -> GroupId {
        GroupId(sha256(&[b"keryx-v1/group", &self.0]))
    }
}

impl fmt::Debug for GroupKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "GroupKey(…)")
    }
}

/// A station's X25519 secret. Serializes as base32: state files and backups
/// holding it are key material.
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct Identity(pub(crate) [u8; KEY_LEN]);
base32_newtype!(Identity);

impl Identity {
    pub fn generate() -> Result<Self, Error> {
        Ok(Self(random_bytes()?))
    }

    pub fn from_secret(secret: [u8; KEY_LEN]) -> Self {
        Self(secret)
    }

    pub fn public(&self) -> PublicKey {
        let s = x25519_dalek::StaticSecret::from(self.0);
        PublicKey(x25519_dalek::PublicKey::from(&s).to_bytes())
    }

    fn dh(&self, peer: &PublicKey) -> Result<Zeroizing<[u8; 32]>, Error> {
        let s = x25519_dalek::StaticSecret::from(self.0);
        let shared = s.diffie_hellman(&x25519_dalek::PublicKey::from(peer.0));
        if !shared.was_contributory() {
            return Err(Error::WeakKey);
        }
        Ok(Zeroizing::new(shared.to_bytes()))
    }
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Identity({:?})", self.public())
    }
}

/// Root key of a link (HKDF PRK).
#[derive(Zeroize, ZeroizeOnDrop)]
pub(crate) struct Root([u8; 32]);

pub(crate) fn pair_root(me: &Identity, peer: &PublicKey) -> Result<Root, Error> {
    let shared = me.dh(peer)?;
    let mine = me.public();
    let (lo, hi) = if mine.0 <= peer.0 {
        (mine, *peer)
    } else {
        (*peer, mine)
    };
    Ok(hkdf_extract(&[shared.as_slice(), &lo.0, &hi.0]))
}

pub(crate) fn group_root(gk: &GroupKey) -> Root {
    hkdf_extract(&[b"group", &gk.0])
}

pub(crate) struct MessageKeys {
    pub enc: [u8; 32],
    pub mac: [u8; 32],
}

impl Drop for MessageKeys {
    fn drop(&mut self) {
        self.enc.zeroize();
        self.mac.zeroize();
    }
}

impl Root {
    fn sender_key(&self, sender: &PublicKey) -> Zeroizing<[u8; 32]> {
        let mut k = Zeroizing::new([0u8; 32]);
        hkdf_expand(&self.0, &[b"send", &sender.0], k.as_mut());
        k
    }

    pub(crate) fn message_keys(&self, sender: &PublicKey, counter: u16) -> MessageKeys {
        let mk = Zeroizing::new(hmac(
            self.sender_key(sender).as_ref(),
            &[&counter.to_be_bytes()],
        ));
        let mut okm = Zeroizing::new([0u8; 64]);
        hkdf_expand(mk.as_ref(), &[b"keys"], okm.as_mut());
        let mut k = MessageKeys {
            enc: [0; 32],
            mac: [0; 32],
        };
        k.enc.copy_from_slice(&okm[..32]);
        k.mac.copy_from_slice(&okm[32..]);
        k
    }

    pub(crate) fn ack_key(&self, acker: &PublicKey) -> Zeroizing<[u8; 32]> {
        let mut k = Zeroizing::new([0u8; 32]);
        hkdf_expand(&self.0, &[b"ack", &acker.0], k.as_mut());
        k
    }
}

pub(crate) fn apply_cipher(enc: &[u8; 32], header: &[u8; HEADER_LEN], body: &mut [u8]) {
    let mut nonce = [0u8; 12];
    nonce[..HEADER_LEN].copy_from_slice(header);
    let mut c = ChaCha20::new(enc.into(), &nonce.into());
    c.apply_keystream(body);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pair_roots_agree_and_differ_per_pair() {
        let a = Identity::from_secret([1; 32]);
        let b = Identity::from_secret([2; 32]);
        let c = Identity::from_secret([3; 32]);
        let ab = pair_root(&a, &b.public()).unwrap();
        let ba = pair_root(&b, &a.public()).unwrap();
        let ac = pair_root(&a, &c.public()).unwrap();
        assert_eq!(ab.0, ba.0);
        assert_ne!(ab.0, ac.0);
    }

    #[test]
    fn low_order_point_is_rejected() {
        let a = Identity::from_secret([1; 32]);
        assert!(matches!(
            pair_root(&a, &PublicKey([0; 32])),
            Err(Error::WeakKey)
        ));
    }

    #[test]
    fn message_keys_depend_on_sender_and_counter() {
        let r = group_root(&GroupKey([9; 32]));
        let a = PublicKey([1; 32]);
        let b = PublicKey([2; 32]);
        assert_ne!(r.message_keys(&a, 0).mac, r.message_keys(&a, 1).mac);
        assert_ne!(r.message_keys(&a, 0).mac, r.message_keys(&b, 0).mac);
        assert_ne!(r.message_keys(&a, 0).enc, r.message_keys(&a, 0).mac);
    }

    #[test]
    fn base32_roundtrip_and_short_ids() {
        let k = Identity::from_secret([7; 32]).public();
        assert_eq!(PublicKey::from_base32(&k.to_base32()).unwrap(), k);
        assert_eq!(
            PublicKey::from_base32(&k.to_base32().to_lowercase()).unwrap(),
            k
        );
        assert!(k.short_id() < 15);
        assert_eq!(k.safety_number().len(), 24);
    }
}
