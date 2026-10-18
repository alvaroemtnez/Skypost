# Text codec — protocol v0

Implemented in `core/crates/nvl-proto/src/textcodec.rs`.

## Design

- Static canonical Huffman code over ~70 symbols: space, lowercase letters,
  ñ, accented vowels, ü, digits, common punctuation, newline, plus three
  control symbols: `SHIFT` (next letter uppercase), `ESC` (raw UTF-8 bytes of
  one character follow) and `END`.
- Weights are rough per-10 000 frequencies for informal Spanish messages.
- Code lengths from a Huffman build with a deterministic tie-break (weight,
  then node id); codes assigned canonically by (length, table order).
- Bit stream is MSB first, ends with `END`, zero-padded to a byte.
- Any Unicode text round-trips (unknown characters go through `ESC`).

Compression happens before encryption, so the codec sees plaintext.

## Measured

"Llegamos al refugio, todo bien. Mañana bajamos por el valle." (60 chars,
62 bytes UTF-8) → 37 bytes, about 4.9 bits per character.

## Improvements (each is a wire change)

1. Word dictionary for frequent Spanish words and fragments ("bien",
   "mañana", "estamos", "que", "de la"), like JS8's `JSC`.
2. Re-train weights on real messages from Phase 0 logs.
3. More stock phrases (cheaper still: 1 byte).

## Stock phrases

`canned.rs` holds 20 phrases (id = index). Append only. `Message::from_text`
uses a phrase id automatically when the text matches one exactly (ignoring
case and outer spaces).
