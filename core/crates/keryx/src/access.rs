//! Pharos access codes (see `docs/spec/pharos.md`, "Access").
//!
//! A Pharos in `code` mode admits field users who know its access code. The
//! code never goes on air: `Register` carries an 8-byte proof bound to both
//! public keys. Signed plaintext stays signed plaintext; nothing is hidden.
//!
//! Anyone who records a `Register` can try to guess the code offline, so the
//! key is stretched with Argon2id and the code should be a passphrase (four
//! random words), not a PIN.

use crate::Error;
use crate::crypto::{PublicKey, argon2id, hmac, sha256_parts, subtle_eq};
use zeroize::Zeroizing;

pub const PROOF_LEN: usize = 8;
/// What `Register` carries when the client has no code.
pub const NO_PROOF: [u8; PROOF_LEN] = [0; PROOF_LEN];

/// Stretched access code of one Pharos. The Pharos derives it once at start;
/// a client once per registration.
pub struct AccessKey(Zeroizing<[u8; 32]>);

impl AccessKey {
    pub fn derive(code: &str, pharos: &PublicKey) -> Result<Self, Error> {
        let code = code.trim();
        if code.is_empty() {
            return Err(Error::Range("empty access code"));
        }
        let salt = sha256_parts(&[b"keryx-v1/access-salt", &pharos.0]);
        Ok(Self(argon2id(code, &salt[..16])?))
    }

    pub fn proof(&self, client: &PublicKey, pharos: &PublicKey) -> [u8; PROOF_LEN] {
        let t = hmac(self.0.as_ref(), &[b"keryx-v1/access", &client.0, &pharos.0]);
        t[..PROOF_LEN].try_into().unwrap()
    }

    pub fn verify(&self, client: &PublicKey, pharos: &PublicKey, proof: &[u8; PROOF_LEN]) -> bool {
        subtle_eq(&self.proof(client, pharos), proof)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proof_binds_code_and_both_keys() {
        let (c, p, q) = (PublicKey([1; 32]), PublicKey([2; 32]), PublicKey([3; 32]));
        let k = AccessKey::derive(" caballo pila grapa correcto ", &p).unwrap();
        let proof = k.proof(&c, &p);
        assert!(
            AccessKey::derive("caballo pila grapa correcto", &p)
                .unwrap()
                .verify(&c, &p, &proof)
        );
        assert!(
            !AccessKey::derive("otra cosa", &p)
                .unwrap()
                .verify(&c, &p, &proof)
        );
        assert!(!k.verify(&q, &p, &proof));
        assert_ne!(
            AccessKey::derive("caballo pila grapa correcto", &q)
                .unwrap()
                .proof(&c, &q),
            proof
        );
        assert!(AccessKey::derive("  ", &p).is_err());
    }
}
