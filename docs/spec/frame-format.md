# Frame format — protocol v1

Implemented in `core/crates/keryx` (`header.rs`, `message.rs`, `ack.rs`,
`poll.rs`, `bridge.rs`, `segment.rs`). Golden vectors:
`core/testvectors/v1.json`. Crypto: [crypto.md](crypto.md).

## Message frame

```
+--------+--------+--------+--------+------------------+-----------------+
| byte 0 | byte 1 | byte 2 | byte 3 | body (0..n)      | tag (8)         |
+--------+--------+--------+--------+------------------+-----------------+
 ver(2)   src(4)   counter (u16, BE)  plaintext or        HMAC-SHA256,
 enc(1)   dst(4)                      ChaCha20 ciphertext truncated
 type(5)
```

| Field | Bits | Meaning |
|---|---|---|
| ver | 2 | Protocol version, 1 |
| enc | 1 | 1 = body encrypted, 0 = signed plaintext |
| type | 5 | Message type, table below |
| src | 4 | Sender short id (hash of its public key), 0..=14 |
| dst | 4 | Recipient or group short id; 15 = broadcast |
| counter | 16 | Sender's counter on this link; selects the message key |

The tag covers header + body as sent. Unauthenticated types have no tag.

### Types (append only)

| Value | Type | Auth | Body |
|---|---|---|---|
| 1 | Text | tag | Spanish static-Huffman text ([text-codec.md](text-codec.md)) |
| 2 | Canned | tag | 1 byte: stock phrase id (`canned.rs`, append only) |
| 3 | StatusOk | tag | empty, or 6-byte position |
| 4 | Sos | tag | empty, or 6-byte position |
| 5 | Position | tag | 6-byte position |
| 6 | Poll | compact | compact layout below |
| 7 | Ack | compact | compact layout below |
| 8 | Discover | none | empty; `dst` = 15, counter = random query id |
| 9 | Announce | none | public key (32) ‖ flags (1) ‖ name (text codec); counter = the query id it answers |
| 10 | Register | tag | public key (32) ‖ alias (text codec) |
| 11 | GroupJoin | tag (group) | public key (32) ‖ alias (text codec) |
| 12 | BridgeOpen | tag | handle (1) ‖ network (1) ‖ address ‖ [display name, text codec] |
| 13 | Bridged | tag | handle (1) ‖ inner type (1) ‖ inner body (types 1–5) |
| 14 | Notice | tag | code (1) ‖ [handle (1)] |

`Register` and `GroupJoin` are tagged with the key of the public key they
carry, which proves possession of the matching secret.

### Announce flags

| Bit | Meaning |
|---|---|
| 0 | Sender is a Pharos |
| 1 | Accepts registrations |
| 2 | Signal bridge |
| 3 | WhatsApp bridge |
| 4 | MeshCore bridge |

### Bridge networks and addresses

| Network | Value | Address encoding |
|---|---|---|
| Signal, phone | 1 | E.164 digits without `+`, BCD, high nibble first, `0xF` nibble terminator, `0xF` padding |
| Signal, username | 2 | length (1) ‖ ASCII |
| WhatsApp, phone | 3 | as Signal phone |
| MeshCore contact | 4 | public key (32) |
| MeshCore channel | 5 | channel secret (16) |

Handles are scoped to one (client, Pharos) pair: 0..=127 are chosen by the
client, 128..=255 by the Pharos. Sending `BridgeOpen` for a handle in use
rebinds it.

### Notice codes

| Code | Meaning | Handle |
|---|---|---|
| 1 | Registered | no |
| 2 | Registration pending operator approval | no |
| 3 | Registration refused | no |
| 4 | Alias already taken on this Pharos | no |
| 16 | Unknown handle: send `BridgeOpen` again | yes |
| 17 | Recipient not reachable on that network | yes |
| 18 | Sending failed, will not retry | yes |
| 19 | Rate limited | yes |
| 20 | Message expired in the queue | yes |
| 21 | Message too long for the bridge | yes |

### Position (6 bytes)

24-bit latitude `round((lat + 90) / 180 × (2²⁴ − 1))`, then 24-bit longitude
`round((lon + 180) / 360 × (2²⁴ − 1))`, big endian. ~1.2 m N-S, < 2.4 m E-W.

## Compact Poll (9 bytes, one JS8 frame)

```
byte 0: ver(2) | 0 | type = 6
byte 1: src(4) | dst(4)          dst = the Pharos (or contact) being polled
byte 2-3: counter (u16, BE)      the poller's counter on that link
byte 4: listen minutes (0..=255) how long the poller keeps receiving
byte 5-8: HMAC-SHA256(mac, bytes 0..5)[0..4]
```

It doubles as the ping. `listen = 0` means "check only, I am not staying".

## ACK (9 bytes, one JS8 frame)

```
byte 0: ver(2) | more(1) | type = 7
byte 1: src(4) | dst(4)          src = the station acknowledging
byte 2: low byte of the acknowledged counter
byte 3: SNR measured by the acknowledging station, dB (i8)
byte 4: clock offset it measured, deciseconds (i8, + = sender late)
byte 5-8: HMAC-SHA256(ack key, bytes 0..5 ‖ full counter BE)[0..4]
```

`more` = the acknowledging Pharos holds queued messages for `dst`.
Duplicates of a delivered frame are re-ACKed.

## Segmentation into PHY frames

- Split into `mtu`-byte chunks (`JS8_FRAME_PAYLOAD_BYTES`, assumed 9).
- Pad the last chunk only if needed: `0x80` then zeros (ISO/IEC 7816-4 style).
- The receiver tries the raw and the unpadded length; the one whose tag
  verifies wins. Unauthenticated frames are parsed strictly by length, so the
  unpadded candidate is tried first for them.
- First/last chunk marking is the PHY's job (JS8 has frame-type bits). Open
  question: confirm those bits are free for this.

## Airtime (8-byte tag, 9-byte frames)

| Frame | Bytes | JS8 frames | Normal | Slow |
|---|---|---|---|---|
| ACK, Poll | 9 | 1 | 15 s | 30 s |
| Discover | 4 | 1 | 15 s | 30 s |
| Stock phrase | 13 | 2 | 30 s | 60 s |
| Estoy bien + position | 18 | 2 | 30 s | 60 s |
| Bridged stock phrase | 15 | 2 | 30 s | 60 s |
| 60-character text | 49 | 6 | 90 s | 180 s |
| Announce ("Casa EA5YYY") | ~44 | 5 | 75 s | 150 s |
| Register (alias "Mamá") | ~48 | 6 | 90 s | 180 s |
| BridgeOpen, Spanish mobile | 20 | 3 | 45 s | 90 s |

## Phase 0 on-air form

JS8Call text layer, directed message: `<TOCALL> NV1 <BASE32(wire frame)>`
(RFC 4648 alphabet, no padding). Replaced by binary JS8 frames in Phase 1.

## Versioning rules

- Any change to bytes on air (layouts, type values, codec weights, phrase
  list order, KDF labels) is a wire change: update this spec, regenerate the
  golden vectors (`KERYX_REGEN=1 cargo test -p keryx --test vectors`), and
  bump `VERSION` unless old and new apps still interoperate.
- Additive changes (new type values, new phrases, new notice codes) keep v1.
- v0 frames (version bits 00) are rejected with `Version(0)`.
