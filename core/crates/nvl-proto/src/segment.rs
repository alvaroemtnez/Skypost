//! Splitting a wire frame into fixed-size PHY frames and back.
//!
//! JS8 frames have a fixed payload size, so the last chunk is padded. Padding
//! is ISO/IEC 7816-4 style (`0x80` then zeros) but only added when needed,
//! which costs nothing for messages that fill their last frame exactly.
//! The receiver therefore gets up to two length candidates and keeps the one
//! whose tag verifies ([`crate::message::decode_padded`]).
//!
//! First/last-frame marking is left to the PHY (JS8 has frame-type bits for
//! this), so it costs no payload bytes here.

/// Split `data` into `mtu`-sized chunks, padding the last one if needed.
pub fn segment(data: &[u8], mtu: usize) -> Vec<Vec<u8>> {
    assert!(mtu > 0, "mtu must be positive");
    let mut padded = data.to_vec();
    if !padded.len().is_multiple_of(mtu) {
        padded.push(0x80);
        while !padded.len().is_multiple_of(mtu) {
            padded.push(0);
        }
    }
    padded.chunks(mtu).map(<[u8]>::to_vec).collect()
}

/// Concatenate received chunks in order.
pub fn reassemble(chunks: &[Vec<u8>]) -> Vec<u8> {
    chunks.concat()
}

/// The data as received, plus the padding-stripped version if it looks padded.
pub fn unpad_candidates(data: &[u8]) -> Vec<&[u8]> {
    let mut out = vec![data];
    let end = data.iter().rposition(|&b| b != 0);
    if let Some(i) = end
        && data[i] == 0x80
    {
        out.push(&data[..i]);
    }
    out
}

/// Number of PHY frames a wire frame of `len` bytes needs.
pub fn frames_needed(len: usize, mtu: usize) -> usize {
    len.div_ceil(mtu)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_multiple_is_not_padded() {
        let d = [1u8; 18];
        let s = segment(&d, 9);
        assert_eq!(s.len(), 2);
        assert_eq!(reassemble(&s), d);
    }

    #[test]
    fn padded_roundtrip_offers_stripped_candidate() {
        let d = [5u8, 6, 7, 0, 0];
        let s = segment(&d, 9);
        assert_eq!(s.len(), 1);
        let r = reassemble(&s);
        let c = unpad_candidates(&r);
        assert!(c.contains(&&d[..]));
    }
}
