//! MSB-first bit packing used by the text codec.

#[derive(Default)]
pub struct BitWriter {
    bytes: Vec<u8>,
    nbits: usize,
}

impl BitWriter {
    pub fn new() -> Self {
        Self::default()
    }

    /// Append the `len` low bits of `code`, most significant first.
    pub fn push(&mut self, code: u32, len: u8) {
        for i in (0..len).rev() {
            let bit = (code >> i) & 1 == 1;
            if self.nbits.is_multiple_of(8) {
                self.bytes.push(0);
            }
            if bit {
                let last = self.bytes.len() - 1;
                self.bytes[last] |= 0x80 >> (self.nbits % 8);
            }
            self.nbits += 1;
        }
    }

    /// Finish, zero-padding the last byte.
    pub fn into_bytes(self) -> Vec<u8> {
        self.bytes
    }
}

pub struct BitReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> BitReader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub fn read_bit(&mut self) -> Option<bool> {
        let byte = *self.data.get(self.pos / 8)?;
        let bit = byte & (0x80 >> (self.pos % 8)) != 0;
        self.pos += 1;
        Some(bit)
    }

    pub fn read_bits(&mut self, n: u8) -> Option<u32> {
        let mut v = 0u32;
        for _ in 0..n {
            v = (v << 1) | u32::from(self.read_bit()?);
        }
        Some(v)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let mut w = BitWriter::new();
        w.push(0b101, 3);
        w.push(0xABCD, 16);
        w.push(1, 1);
        let bytes = w.into_bytes();
        assert_eq!(bytes.len(), 3);
        let mut r = BitReader::new(&bytes);
        assert_eq!(r.read_bits(3), Some(0b101));
        assert_eq!(r.read_bits(16), Some(0xABCD));
        assert_eq!(r.read_bits(1), Some(1));
    }
}
