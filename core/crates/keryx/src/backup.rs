//! Passphrase-encrypted backups (see `docs/spec/crypto.md`, "Backups").
//!
//! The caller decides what goes in (normally the serialized keyring plus app
//! settings). After restoring a keyring, call
//! [`crate::keyring::Keyring::after_restore`].

use crate::Error;
use crate::crypto::{hkdf_expand, hmac, random_bytes, subtle_eq};
use argon2::{Algorithm, Argon2, Params, Version};
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};
use zeroize::Zeroizing;

const MAGIC: &[u8] = b"SKYPOSTB1";
const SALT_LEN: usize = 16;
const TAG_LEN: usize = 32;
/// Argon2id memory in KiB (19 MiB), passes and lanes: the OWASP minimum,
/// which a low-end phone handles in about a second.
const M_COST: u32 = 19 * 1024;
const T_COST: u32 = 2;
const P_COST: u32 = 1;

fn keys(passphrase: &str, salt: &[u8]) -> Result<Zeroizing<[u8; 64]>, Error> {
    let params = Params::new(M_COST, T_COST, P_COST, Some(32)).map_err(|_| Error::Backup)?;
    let mut prk = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(passphrase.as_bytes(), salt, prk.as_mut())
        .map_err(|_| Error::Backup)?;
    let mut okm = Zeroizing::new([0u8; 64]);
    hkdf_expand(prk.as_ref(), &[b"backup"], okm.as_mut());
    Ok(okm)
}

fn cipher(enc: &[u8], data: &mut [u8]) {
    let key: [u8; 32] = enc.try_into().unwrap();
    ChaCha20::new(&key.into(), &[0u8; 12].into()).apply_keystream(data);
}

pub fn seal(passphrase: &str, plaintext: &[u8]) -> Result<Vec<u8>, Error> {
    if passphrase.chars().count() < 8 {
        return Err(Error::Range("passphrase must have at least 8 characters"));
    }
    let salt: [u8; SALT_LEN] = random_bytes()?;
    let k = keys(passphrase, &salt)?;
    let mut out = [MAGIC, &salt].concat();
    let mut body = plaintext.to_vec();
    cipher(&k[..32], &mut body);
    out.extend_from_slice(&body);
    let tag = hmac(&k[32..], &[&out]);
    out.extend_from_slice(&tag);
    Ok(out)
}

pub fn open(passphrase: &str, data: &[u8]) -> Result<Zeroizing<Vec<u8>>, Error> {
    let head = MAGIC.len() + SALT_LEN;
    if data.len() < head + TAG_LEN || !data.starts_with(MAGIC) {
        return Err(Error::Backup);
    }
    let (signed, tag) = data.split_at(data.len() - TAG_LEN);
    let k = keys(passphrase, &data[MAGIC.len()..head])?;
    if !subtle_eq(&hmac(&k[32..], &[signed]), tag) {
        return Err(Error::Backup);
    }
    let mut body = Zeroizing::new(signed[head..].to_vec());
    cipher(&k[..32], &mut body);
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_wrong_passphrase_and_tamper() {
        let b = seal("caballo correcto", b"{\"keyring\":1}").unwrap();
        assert_eq!(
            open("caballo correcto", &b).unwrap().as_slice(),
            b"{\"keyring\":1}"
        );
        assert_eq!(open("caballo incorrecto", &b), Err(Error::Backup));
        let mut t = b.clone();
        *t.last_mut().unwrap() ^= 1;
        assert_eq!(open("caballo correcto", &t), Err(Error::Backup));
        assert!(seal("corta", b"x").is_err());
    }
}
