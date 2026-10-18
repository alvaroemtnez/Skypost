//! Static Huffman codec for short Spanish messages (see `docs/spec/text-codec.md`).
//!
//! Lowercase letters, accents, digits and common punctuation have codes built
//! from fixed Spanish frequency weights. Uppercase is `SHIFT` + lowercase.
//! Anything else is `ESC` + the raw UTF-8 bytes of that one character.
//! The bit stream ends with `END`, then zero bits to the byte boundary.
//!
//! WIRE FORMAT: the weight table below defines the code. Changing any weight,
//! symbol or the tie-break order changes every encoded message, which needs a
//! protocol version bump and new golden vectors.

use crate::Error;
use crate::bits::{BitReader, BitWriter};
use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};
use std::sync::OnceLock;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum Sym {
    Char(char),
    Shift,
    Esc,
    End,
}

/// (symbol, weight). Weights are rough per-10 000 frequencies for informal
/// Spanish text messages, spaces included. Order is the canonical tie-break.
const TABLE: &[(Sym, u32)] = &[
    (Sym::Char(' '), 1700),
    (Sym::Char('e'), 1150),
    (Sym::Char('a'), 1050),
    (Sym::Char('o'), 750),
    (Sym::Char('s'), 650),
    (Sym::Char('n'), 560),
    (Sym::Char('r'), 540),
    (Sym::Char('i'), 450),
    (Sym::Char('l'), 430),
    (Sym::Char('d'), 420),
    (Sym::Char('t'), 380),
    (Sym::Char('c'), 340),
    (Sym::Char('u'), 330),
    (Sym::Char('m'), 250),
    (Sym::Shift, 250),
    (Sym::Char('p'), 200),
    (Sym::End, 200),
    (Sym::Char('.'), 120),
    (Sym::Char(','), 120),
    (Sym::Char('b'), 110),
    (Sym::Char('g'), 90),
    (Sym::Char('v'), 85),
    (Sym::Char('y'), 80),
    (Sym::Char('q'), 75),
    (Sym::Char('ó'), 70),
    (Sym::Char('h'), 60),
    (Sym::Char('f'), 55),
    (Sym::Char('z'), 40),
    (Sym::Char('j'), 40),
    (Sym::Char('á'), 40),
    (Sym::Char('é'), 35),
    (Sym::Char('í'), 35),
    (Sym::Char('0'), 35),
    (Sym::Char('1'), 35),
    (Sym::Char('2'), 30),
    (Sym::Char('ñ'), 25),
    (Sym::Char('?'), 25),
    (Sym::Char('!'), 25),
    (Sym::Char('3'), 20),
    (Sym::Char('4'), 20),
    (Sym::Char('5'), 20),
    (Sym::Char('6'), 20),
    (Sym::Char('7'), 20),
    (Sym::Char('8'), 20),
    (Sym::Char('9'), 20),
    (Sym::Char('¿'), 20),
    (Sym::Char(':'), 20),
    (Sym::Char('ú'), 15),
    (Sym::Char('¡'), 15),
    (Sym::Char('-'), 15),
    (Sym::Char('\n'), 15),
    (Sym::Char('x'), 12),
    (Sym::Char('/'), 10),
    (Sym::Char('k'), 5),
    (Sym::Char('w'), 5),
    (Sym::Esc, 5),
    (Sym::Char(';'), 3),
    (Sym::Char('\''), 3),
    (Sym::Char('"'), 3),
    (Sym::Char('('), 3),
    (Sym::Char(')'), 3),
    (Sym::Char('@'), 3),
    (Sym::Char('#'), 3),
    (Sym::Char('+'), 3),
    (Sym::Char('%'), 3),
    (Sym::Char('ü'), 2),
    (Sym::Char('*'), 2),
    (Sym::Char('&'), 2),
    (Sym::Char('='), 2),
];

struct Codebook {
    encode: HashMap<Sym, (u32, u8)>,
    decode: HashMap<(u8, u32), Sym>,
    max_len: u8,
}

fn codebook() -> &'static Codebook {
    static CB: OnceLock<Codebook> = OnceLock::new();
    CB.get_or_init(build_codebook)
}

/// Huffman code lengths with a deterministic tie-break (weight, then node id),
/// then canonical code assignment sorted by (length, table index).
fn build_codebook() -> Codebook {
    let n = TABLE.len();
    let mut parent = vec![usize::MAX; 2 * n - 1];
    let mut heap = BinaryHeap::new();
    for (i, (_, w)) in TABLE.iter().enumerate() {
        heap.push(Reverse((u64::from(*w), i)));
    }
    let mut next = n;
    while heap.len() > 1 {
        let Reverse((wa, a)) = heap.pop().unwrap();
        let Reverse((wb, b)) = heap.pop().unwrap();
        parent[a] = next;
        parent[b] = next;
        heap.push(Reverse((wa + wb, next)));
        next += 1;
    }
    let lengths: Vec<u8> = (0..n)
        .map(|mut i| {
            let mut d = 0u8;
            while parent[i] != usize::MAX {
                i = parent[i];
                d += 1;
            }
            d
        })
        .collect();

    let mut order: Vec<usize> = (0..n).collect();
    order.sort_by_key(|&i| (lengths[i], i));
    let mut encode = HashMap::new();
    let mut decode = HashMap::new();
    let mut code = 0u32;
    let mut prev_len = lengths[order[0]];
    for (k, &i) in order.iter().enumerate() {
        let len = lengths[i];
        if k > 0 {
            code = (code + 1) << (len - prev_len);
        }
        prev_len = len;
        encode.insert(TABLE[i].0, (code, len));
        decode.insert((len, code), TABLE[i].0);
    }
    let max_len = *lengths.iter().max().unwrap();
    Codebook {
        encode,
        decode,
        max_len,
    }
}

/// Uppercase letter whose lowercase has its own code and maps back exactly.
fn shiftable(c: char) -> Option<char> {
    let mut lower = c.to_lowercase();
    let l = lower.next()?;
    if lower.next().is_some() || l == c {
        return None;
    }
    let mut upper = l.to_uppercase();
    (upper.next() == Some(c) && upper.next().is_none()).then_some(l)
}

pub fn encode(text: &str) -> Vec<u8> {
    let cb = codebook();
    let mut w = BitWriter::new();
    let emit = |w: &mut BitWriter, s: Sym| {
        let (code, len) = cb.encode[&s];
        w.push(code, len);
    };
    for c in text.chars() {
        if cb.encode.contains_key(&Sym::Char(c)) {
            emit(&mut w, Sym::Char(c));
        } else if let Some(l) = shiftable(c).filter(|l| cb.encode.contains_key(&Sym::Char(*l))) {
            emit(&mut w, Sym::Shift);
            emit(&mut w, Sym::Char(l));
        } else {
            emit(&mut w, Sym::Esc);
            let mut buf = [0u8; 4];
            for b in c.encode_utf8(&mut buf).bytes() {
                w.push(u32::from(b), 8);
            }
        }
    }
    emit(&mut w, Sym::End);
    w.into_bytes()
}

fn read_sym(r: &mut BitReader, cb: &Codebook) -> Result<Sym, Error> {
    let mut code = 0u32;
    for len in 1..=cb.max_len {
        let bit = r
            .read_bit()
            .ok_or(Error::Malformed("text ended without END"))?;
        code = (code << 1) | u32::from(bit);
        if let Some(s) = cb.decode.get(&(len, code)) {
            return Ok(*s);
        }
    }
    Err(Error::Malformed("invalid text code"))
}

pub fn decode(bytes: &[u8]) -> Result<String, Error> {
    let cb = codebook();
    let mut r = BitReader::new(bytes);
    let mut out = String::new();
    loop {
        match read_sym(&mut r, cb)? {
            Sym::End => return Ok(out),
            Sym::Char(c) => out.push(c),
            Sym::Shift => match read_sym(&mut r, cb)? {
                Sym::Char(c) => out.extend(c.to_uppercase()),
                _ => return Err(Error::Malformed("SHIFT not followed by a letter")),
            },
            Sym::Esc => {
                let b0 = r.read_bits(8).ok_or(Error::Malformed("truncated escape"))? as u8;
                let extra = match b0 {
                    0x00..=0x7F => 0,
                    0xC0..=0xDF => 1,
                    0xE0..=0xEF => 2,
                    0xF0..=0xF7 => 3,
                    _ => return Err(Error::Malformed("bad UTF-8 lead byte")),
                };
                let mut buf = vec![b0];
                for _ in 0..extra {
                    buf.push(r.read_bits(8).ok_or(Error::Malformed("truncated escape"))? as u8);
                }
                let s = std::str::from_utf8(&buf).map_err(|_| Error::Malformed("bad UTF-8"))?;
                out.push_str(s);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_spanish_and_escapes() {
        for s in [
            "",
            "Llegamos al refugio, todo bien. Mañana bajamos por el valle.",
            "¿Qué tal? ¡Ánimo! Ñandú, pingüino, 112.",
            "Emoji 🙂 y € y Ł",
            "MAYÚSCULAS",
        ] {
            assert_eq!(decode(&encode(s)).unwrap(), s, "{s}");
        }
    }

    #[test]
    fn compresses_typical_text_to_about_half() {
        let s = "Llegamos al refugio, todo bien. Mañana bajamos por el valle.";
        let n = encode(s).len();
        // 60 chars (62 bytes of UTF-8) measure 37 bytes: ~4.9 bits per char.
        // A word dictionary (like JS8's JSC) is the next improvement.
        assert!(n <= 37, "encoded to {n} bytes");
    }

    #[test]
    fn code_is_prefix_free_and_complete() {
        let cb = codebook();
        assert_eq!(cb.encode.len(), TABLE.len());
        // Kraft sum of a complete prefix code is exactly 1.
        let kraft: f64 = cb
            .encode
            .values()
            .map(|&(_, l)| 0.5f64.powi(l as i32))
            .sum();
        assert!((kraft - 1.0).abs() < 1e-12);
    }
}
