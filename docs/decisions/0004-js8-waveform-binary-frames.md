# 0004. JS8 waveform with our own binary frames as the robust lane

Status: accepted (2026-10-18)

## Context

JS8 decodes far below the noise and runs on the QDX. JS8Call's text layer (Huffman varicode + `JSC` dictionary) is tuned for English text and wastes bits on ciphertext.

## Decision

Use the JS8 waveform and LDPC (all four speeds) as a binary PHY with an assumed 9-byte payload per frame. Our own codec runs before encryption. Phase 0 uses JS8Call's text layer with base32 as a stopgap.

## Consequences

- QDX-compatible, best weak-signal performance.
- Must confirm payload size and frame-type bits in the engine.
- Our frames are unreadable to JS8Call users: use our own dial frequencies.
