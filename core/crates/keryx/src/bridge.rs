//! Addresses of bridged contacts and notice codes (see
//! `docs/spec/frame-format.md`, "Bridge networks and addresses").

use crate::Error;
use serde::{Deserialize, Serialize};

/// A contact on a network the Pharos bridges to.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(tag = "network", content = "address", rename_all = "snake_case")]
pub enum Address {
    /// E.164 digits without `+`.
    SignalPhone(String),
    SignalUsername(String),
    /// E.164 digits without `+`.
    WhatsappPhone(String),
    /// MeshCore public key (Ed25519, 32 bytes).
    MeshcoreContact(#[serde(with = "hex32")] [u8; 32]),
    /// MeshCore channel secret (16 bytes).
    MeshcoreChannel(#[serde(with = "hex16")] [u8; 16]),
}

/// Network codes on air. Append only.
pub mod network {
    pub const SIGNAL_PHONE: u8 = 1;
    pub const SIGNAL_USERNAME: u8 = 2;
    pub const WHATSAPP_PHONE: u8 = 3;
    pub const MESHCORE_CONTACT: u8 = 4;
    pub const MESHCORE_CHANNEL: u8 = 5;
}

/// `Notice` codes. Append only.
pub mod notice {
    pub const REGISTERED: u8 = 1;
    pub const REGISTRATION_PENDING: u8 = 2;
    pub const REGISTRATION_REFUSED: u8 = 3;
    pub const ALIAS_TAKEN: u8 = 4;
    pub const UNKNOWN_HANDLE: u8 = 16;
    pub const RECIPIENT_UNREACHABLE: u8 = 17;
    pub const SEND_FAILED: u8 = 18;
    pub const RATE_LIMITED: u8 = 19;
    pub const EXPIRED: u8 = 20;
    pub const TOO_LONG: u8 = 21;

    /// Codes that carry a bridge handle.
    pub fn has_handle(code: u8) -> bool {
        code >= 16
    }
}

/// First handle a Pharos assigns; below it, handles belong to the client.
pub const PHAROS_HANDLE_BASE: u8 = 128;

fn check_phone(digits: &str) -> Result<(), Error> {
    if digits.is_empty() || digits.len() > 15 || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return Err(Error::Range("phone must be 1..=15 E.164 digits, no '+'"));
    }
    Ok(())
}

/// BCD digits, high nibble first, 0xF terminator and padding.
fn encode_bcd(digits: &str, out: &mut Vec<u8>) {
    let mut nibbles: Vec<u8> = digits.bytes().map(|b| b - b'0').collect();
    nibbles.push(0xF);
    if nibbles.len() % 2 == 1 {
        nibbles.push(0xF);
    }
    out.extend(nibbles.chunks(2).map(|p| (p[0] << 4) | p[1]));
}

fn decode_bcd(b: &[u8]) -> Result<(String, usize), Error> {
    let mut s = String::new();
    for (i, byte) in b.iter().enumerate() {
        for n in [byte >> 4, byte & 0xF] {
            match n {
                0..=9 => s.push(char::from(b'0' + n)),
                0xF => {
                    check_phone(&s)?;
                    return Ok((s, i + 1));
                }
                _ => return Err(Error::Malformed("bad BCD digit")),
            }
        }
    }
    Err(Error::Malformed("phone without terminator"))
}

impl Address {
    /// Phone number from user input: keeps digits, drops `+`, spaces, dashes.
    pub fn normalize_phone(input: &str) -> String {
        input.chars().filter(char::is_ascii_digit).collect()
    }

    pub fn network(&self) -> u8 {
        match self {
            Self::SignalPhone(_) => network::SIGNAL_PHONE,
            Self::SignalUsername(_) => network::SIGNAL_USERNAME,
            Self::WhatsappPhone(_) => network::WHATSAPP_PHONE,
            Self::MeshcoreContact(_) => network::MESHCORE_CONTACT,
            Self::MeshcoreChannel(_) => network::MESHCORE_CHANNEL,
        }
    }

    /// Network byte then the address.
    pub fn encode(&self, out: &mut Vec<u8>) -> Result<(), Error> {
        out.push(self.network());
        match self {
            Self::SignalPhone(d) | Self::WhatsappPhone(d) => {
                check_phone(d)?;
                encode_bcd(d, out);
            }
            Self::SignalUsername(u) => {
                if u.is_empty() || u.len() > 64 || !u.is_ascii() {
                    return Err(Error::Range("username must be 1..=64 ASCII bytes"));
                }
                out.push(u.len() as u8);
                out.extend_from_slice(u.as_bytes());
            }
            Self::MeshcoreContact(k) => out.extend_from_slice(k),
            Self::MeshcoreChannel(s) => out.extend_from_slice(s),
        }
        Ok(())
    }

    /// Parse network byte + address; returns the bytes consumed.
    pub fn decode(b: &[u8]) -> Result<(Self, usize), Error> {
        let (&net, rest) = b.split_first().ok_or(Error::TooShort)?;
        let fixed = |n: usize| rest.get(..n).ok_or(Error::TooShort);
        Ok(match net {
            network::SIGNAL_PHONE | network::WHATSAPP_PHONE => {
                let (d, n) = decode_bcd(rest)?;
                let a = if net == network::SIGNAL_PHONE {
                    Self::SignalPhone(d)
                } else {
                    Self::WhatsappPhone(d)
                };
                (a, 1 + n)
            }
            network::SIGNAL_USERNAME => {
                let (&len, rest) = rest.split_first().ok_or(Error::TooShort)?;
                let u = rest.get(..usize::from(len)).ok_or(Error::TooShort)?;
                let u = std::str::from_utf8(u).map_err(|_| Error::Malformed("username"))?;
                (Self::SignalUsername(u.to_owned()), 2 + usize::from(len))
            }
            network::MESHCORE_CONTACT => {
                (Self::MeshcoreContact(fixed(32)?.try_into().unwrap()), 33)
            }
            network::MESHCORE_CHANNEL => {
                (Self::MeshcoreChannel(fixed(16)?.try_into().unwrap()), 17)
            }
            _ => return Err(Error::Malformed("unknown bridge network")),
        })
    }

    /// For logs and bot texts.
    pub fn display(&self) -> String {
        match self {
            Self::SignalPhone(d) | Self::WhatsappPhone(d) => format!("+{d}"),
            Self::SignalUsername(u) => u.clone(),
            Self::MeshcoreContact(k) => format!("meshcore:{}", hex(&k[..6])),
            Self::MeshcoreChannel(s) => format!("meshcore-canal:{}", hex(&s[..4])),
        }
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex<const N: usize>(s: &str) -> Result<[u8; N], String> {
    if s.len() != 2 * N {
        return Err(format!("expected {} hex characters", 2 * N));
    }
    let mut out = [0u8; N];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).map_err(|e| e.to_string())?;
    }
    Ok(out)
}

macro_rules! hex_serde {
    ($m:ident, $n:literal) => {
        mod $m {
            use serde::{Deserialize, Deserializer, Serializer};
            pub fn serialize<S: Serializer>(b: &[u8; $n], s: S) -> Result<S::Ok, S::Error> {
                s.serialize_str(&super::hex(b))
            }
            pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[u8; $n], D::Error> {
                super::unhex(&String::deserialize(d)?).map_err(serde::de::Error::custom)
            }
        }
    };
}
hex_serde!(hex32, 32);
hex_serde!(hex16, 16);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_roundtrip_and_phone_is_bcd() {
        let cases = [
            Address::SignalPhone("34600123456".into()),
            Address::WhatsappPhone("346001234567".into()),
            Address::SignalUsername("mama.01".into()),
            Address::MeshcoreContact([7; 32]),
            Address::MeshcoreChannel([8; 16]),
        ];
        for a in cases {
            let mut b = Vec::new();
            a.encode(&mut b).unwrap();
            b.extend_from_slice(b"rest");
            let (got, n) = Address::decode(&b).unwrap();
            assert_eq!(got, a);
            assert_eq!(&b[n..], b"rest");
        }
        let mut b = Vec::new();
        Address::SignalPhone("34600123456".into())
            .encode(&mut b)
            .unwrap();
        assert_eq!(b, [1, 0x34, 0x60, 0x01, 0x23, 0x45, 0x6F]);
        assert_eq!(Address::normalize_phone("+34 600-12 34 56"), "34600123456");
        assert!(
            Address::SignalPhone("+34".into())
                .encode(&mut Vec::new())
                .is_err()
        );
    }
}
