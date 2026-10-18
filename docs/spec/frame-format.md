# Frame format — protocol v0

Implemented in `core/crates/keryx` (`header.rs`, `message.rs`, `ack.rs`,
`segment.rs`). Golden vectors: `core/testvectors/v0.json`.

## Message frame

```
+--------+--------+--------+--------+------------------+-----------------+
| byte 0 | byte 1 | byte 2 | byte 3 | body (0..n)      | tag (8 or 16)   |
+--------+--------+--------+--------+------------------+-----------------+
 ver(2)   src(4)   counter (u16, BE)  plaintext or        HMAC-SHA256,
 enc(1)   dst(4)                      ChaCha20 ciphertext truncated
 type(5)
```

| Field | Bits | Meaning |
|---|---|---|
| ver | 2 | Protocol version, 0 |
| enc | 1 | 1 = body encrypted, 0 = signed plaintext |
| type | 5 | Message type, table below |
| src | 4 | Sender member id, 0..=14 (0 = base by convention) |
| dst | 4 | Recipient id; 15 = whole group / base |
| counter | 16 | Sender's message counter; also selects the ratchet key |

The tag always covers header + body as sent. Tag length is a group setting
(provisioning code `t8` or `t16`), not signalled per frame.

### Types (append only)

| Value | Type | Body |
|---|---|---|
| 1 | Text | Spanish static-Huffman text ([text-codec.md](text-codec.md)) |
| 2 | Canned | 1 byte: stock phrase id (`canned.rs`, append only) |
| 3 | StatusOk | empty, or 6-byte position |
| 4 | Sos | empty, or 6-byte position |
| 5 | Position | 6-byte position |
| 6 | Poll | empty: "anything queued for me?" |
| 7 | Ack | compact layout below |

### Position (6 bytes)

24-bit latitude `round((lat + 90) / 180 × (2²⁴ − 1))`, then 24-bit longitude
`round((lon + 180) / 360 × (2²⁴ − 1))`, big endian. ~1.2 m N-S, < 2.4 m E-W.

## ACK frame (9 bytes, one JS8 frame)

```
byte 0: ver(2) | 0 | type = 7
byte 1: src(4) | dst(4)          src = the station acknowledging
byte 2: low byte of the acknowledged counter
byte 3: SNR measured by the acknowledging station, dB (i8)
byte 4: clock offset it measured, deciseconds (i8, + = sender late)
byte 5-8: HMAC-SHA256(ack_key, bytes 0..5)[0..4]
```

The ACK key is static per group, so ACKs are replayable; the worst case is a
sender believing an old message arrived. Duplicates of a delivered frame are
re-ACKed (the sender missed the first ACK).

## Segmentation into PHY frames

- Split into `mtu`-byte chunks (`JS8_FRAME_PAYLOAD_BYTES`, assumed 9).
- Pad the last chunk only if needed: `0x80` then zeros (ISO/IEC 7816-4 style).
- The receiver tries the raw and the unpadded length; the one whose tag
  verifies wins (`decode_padded`). Exact-multiple messages cost no padding.
- First/last chunk marking is the PHY's job (JS8 has frame-type bits). Open
  question: confirm those bits are free for this.

## Phase 0 on-air form

JS8Call text layer, directed message: `<TOCALL> NV0 <BASE32(wire frame)>`
(RFC 4648 alphabet, no padding). Replaced by binary JS8 frames in Phase 1.

## Versioning rules

- Any change to bytes on air (layouts, type values, codec weights, phrase
  list order, KDF labels) is a wire change: update this spec, regenerate the
  golden vectors (`KERYX_REGEN=1 cargo test -p keryx --test vectors`), and
  bump `VERSION` unless old and new apps still interoperate.
- Additive changes (new type values, new phrases appended) keep v0.
