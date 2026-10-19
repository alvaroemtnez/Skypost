//! `skypost:` URIs for QR codes, clipboard and deep links (see
//! `docs/spec/identity.md`).

use crate::Error;
use crate::crypto::{GroupKey, PublicKey};
use crate::keyring::{Card, PeerKind};

pub const SCHEME: &str = "skypost:";

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SkypostUri {
    /// A person or a Pharos.
    Id(Card),
    /// A group invitation. Secret: whoever has it is a member.
    Group { key: GroupKey, name: String },
}

/// 1–16 characters: letters (any script), digits, `_`, `-`, `.`.
pub fn valid_alias(a: &str) -> bool {
    let n = a.chars().count();
    (1..=16).contains(&n) && a.chars().all(|c| c.is_alphanumeric() || "_-.".contains(c))
}

/// 3–10 characters: A–Z, 0–9 and `/` (portable prefixes and suffixes).
pub fn valid_callsign(c: &str) -> bool {
    (3..=10).contains(&c.len())
        && c.bytes()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'/')
        && c.bytes().any(|b| b.is_ascii_digit())
}

fn pct_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(char::from(b));
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn pct_decode(s: &str) -> Result<String, Error> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' => {
                let h = s
                    .get(i + 1..i + 3)
                    .ok_or(Error::Malformed("bad % escape"))?;
                out.push(u8::from_str_radix(h, 16).map_err(|_| Error::Malformed("bad % escape"))?);
                i += 3;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8(out).map_err(|_| Error::Malformed("URI is not UTF-8"))
}

impl SkypostUri {
    pub fn parse(s: &str) -> Result<Self, Error> {
        let s = s.trim();
        let rest = s
            .get(..SCHEME.len())
            .filter(|p| p.eq_ignore_ascii_case(SCHEME))
            .map(|_| &s[SCHEME.len()..])
            .ok_or(Error::Malformed("not a skypost: URI"))?;
        let rest = rest.trim_start_matches("//");
        let (path, query) = rest.split_once('?').unwrap_or((rest, ""));
        let (kind, data) = path
            .split_once('/')
            .ok_or(Error::Malformed("missing key"))?;
        let mut params = std::collections::HashMap::new();
        for kv in query.split('&').filter(|kv| !kv.is_empty()) {
            let (k, v) = kv.split_once('=').unwrap_or((kv, ""));
            params.insert(k.to_owned(), pct_decode(v)?);
        }
        let name = params.remove("n").unwrap_or_default();
        match kind.to_ascii_lowercase().as_str() {
            "id" => {
                if !valid_alias(&name) {
                    return Err(Error::Malformed("invalid alias"));
                }
                let callsign = params.remove("c").map(|c| c.to_ascii_uppercase());
                if callsign.as_deref().is_some_and(|c| !valid_callsign(c)) {
                    return Err(Error::Malformed("invalid callsign"));
                }
                let kind = match params.get("k").map(String::as_str) {
                    Some("pharos") => PeerKind::Pharos,
                    _ => PeerKind::Person,
                };
                Ok(Self::Id(Card {
                    key: PublicKey::from_base32(data)?,
                    alias: name,
                    callsign,
                    kind,
                }))
            }
            "group" => {
                if name.is_empty() || name.chars().count() > 32 {
                    return Err(Error::Malformed("group name must be 1..=32 characters"));
                }
                Ok(Self::Group {
                    key: GroupKey::from_base32(data)?,
                    name,
                })
            }
            _ => Err(Error::Malformed("unknown skypost: URI kind")),
        }
    }

    pub fn format(&self) -> String {
        match self {
            Self::Id(c) => {
                let mut s = format!(
                    "{SCHEME}id/{}?n={}",
                    c.key.to_base32(),
                    pct_encode(&c.alias)
                );
                if let Some(cs) = &c.callsign {
                    s += &format!("&c={}", pct_encode(cs));
                }
                if c.kind == PeerKind::Pharos {
                    s += "&k=pharos";
                }
                s
            }
            Self::Group { key, name } => {
                format!("{SCHEME}group/{}?n={}", key.to_base32(), pct_encode(name))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::Identity;

    #[test]
    fn id_and_group_roundtrip() {
        let card = Card {
            key: Identity::from_secret([4; 32]).public(),
            alias: "Álvaro".into(),
            callsign: Some("EA5XXX".into()),
            kind: PeerKind::Pharos,
        };
        let u = SkypostUri::Id(card);
        let s = u.format();
        assert!(
            s.starts_with("skypost:id/") && s.contains("n=%C3%81lvaro") && s.ends_with("&k=pharos")
        );
        assert_eq!(SkypostUri::parse(&s).unwrap(), u);
        let g = SkypostUri::Group {
            key: GroupKey([1; 32]),
            name: "Familia Pérez".into(),
        };
        assert_eq!(SkypostUri::parse(&g.format()).unwrap(), g);
    }

    #[test]
    fn rejects_bad_input_and_ignores_unknown_params() {
        let k = Identity::from_secret([4; 32]).public().to_base32();
        assert!(SkypostUri::parse(&format!("skypost:id/{k}?n=a%20b")).is_err());
        assert!(SkypostUri::parse(&format!("skypost:id/{k}?n=ana&c=nope!")).is_err());
        assert!(SkypostUri::parse("https://example.org").is_err());
        assert!(SkypostUri::parse(&format!("SKYPOST://id/{k}?n=ana&x=1")).is_ok());
        assert!(valid_alias("mamá_2") && !valid_alias("") && !valid_alias("a b"));
    }
}
