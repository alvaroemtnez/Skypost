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
| 9 | Announce | none | public key (32) ‖ flags (1) ‖ alias, then a space and the callsign if any (text codec); counter = the query id it answers |
| 10 | Register | tag | public key (32) ‖ access proof (8, zeros if none) ‖ alias (text codec) |
| 11 | GroupJoin | tag (group) | public key (32) ‖ alias (text codec) |
| 12 | BridgeOpen | tag | handle (1) ‖ network (1) ‖ address ‖ [display name, text codec] |
| 13 | Bridged | tag | handle (1) ‖ inner type (1) ‖ inner body (types 1–5) |
| 14 | Notice | tag | code (1) ‖ [handle (1)] |
| 15 | Admin | tag | admin command, see "Admin and AdminReply" |
| 16 | AdminReply | tag | admin reply or notice, see "Admin and AdminReply" |

`Register` and `GroupJoin` are tagged with the key of the public key they
carry, which proves possession of the matching secret. They are always
signed plaintext (`enc` = 0): the receiver needs the key in clear to find the
link.

### Announce flags

| Bit | Meaning |
|---|---|
| 0 | Sender is a Pharos |
| 1 | An access code is accepted: a valid proof admits at once ([pharos.md](pharos.md#access)) |
| 2 | Signal bridge |
| 3 | WhatsApp bridge |
| 4 | MeshCore bridge |
| 5 | Closed: registered users and accepted contacts only |
| 6 | Closed but taking access requests (decision 0021) |

### Bridge networks and addresses

| Network | Value | Address encoding |
|---|---|---|
| Signal, phone | 1 | E.164 digits without `+`, BCD, high nibble first, `0xF` nibble terminator, `0xF` padding |
| Signal, username | 2 | length (1) ‖ ASCII |
| WhatsApp, phone | 3 | as Signal phone |
| MeshCore contact | 4 | public key (32) |
| (reserved, was MeshCore channel) | 5 | never reuse |

Groups and channels are never bridged.

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
| 5 | Access code needed (proof missing or wrong) | no |
| 6 | Emergency mode: encrypted messages only (sent encrypted, once) | no |
| 16 | Unknown handle: send `BridgeOpen` again | yes |
| 17 | Recipient not reachable on that network | yes |
| 18 | Sending failed, will not retry | yes |
| 19 | Rate limited | yes |
| 20 | Message expired in the queue | yes |
| 21 | Message too long for the bridge | yes |

### Admin and AdminReply

Decision [0021](../decisions/0021-pharos-access-v2.md). Body of `Admin`: an
opcode byte, then arguments. Opcodes are append only.

| Opcode | Command | Arguments |
|---|---|---|
| 0 | status | — |
| 1 | requests (list pending) | — |
| 2 / 3 | accept / refuse | request number (1) |
| 4 | clients | — |
| 5 | admins | — |
| 6 / 7 | block / unblock | target |
| 8 | allow (Signal or WhatsApp only) | network (1) ‖ address |
| 9 / 10 | add admin / remove admin | target |
| 11 | login (encrypted frames only) | password (text codec) |
| `0x40 \| switch << 1 \| on` | set a switch | — (one byte in all) |

Switches: 0 open, 1 requests, 2 login, 3 emergency, 4 stealth. A switch byte
holds them as bits (bit = switch number).

Target: `0x00` ‖ alias (text codec, to the end); or network (1) ‖ address as
in `BridgeOpen`; or `0x10` ‖ the first 4 bytes of a MeshCore key the Pharos
knows.

Body of `AdminReply`: a kind byte, then:

| Kind | Reply | Body |
|---|---|---|
| 0 | done | opcode (1) ‖ outcome (1) ‖ [subject, text codec] |
| 1 | status | switches (1) ‖ registered (1) ‖ requests (1) ‖ listening (1) ‖ queued (u16) ‖ authorised contacts (u16) |
| 2 | requests | per request: number (1) ‖ who (1) ‖ name |
| 3 | clients | per user: status (1) ‖ minutes since heard (u16, `0xFFFF` never) ‖ alias |
| 4 | admins | per admin: who (1) ‖ how (1: 0 SSH, 1 by an admin, 2 password) ‖ name |
| 5 | new requests (to admins) | networks mask (1, bit = who) ‖ pending (1) |
| 6 | switches changed (to admins) | switches (1) |
| 7 | admin changed (to admins) | added (1) ‖ who (1) ‖ name (text codec) |

Names in lists are a length byte ‖ text codec. Who: 0 radio, 1 Signal, 2
WhatsApp, 3 MeshCore. Outcomes: 0 ok, 1 not admin, 2 unknown, 3 wrong
password, 4 password in clear (refused), 5 last admin, 6 login off, 7 locked
(too many wrong passwords), 8 refused. Client status: 0 registered, 1 pending,
2 refused, 3 unregistered, 4 blocked.

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
| Announce ("casa EA5YYY") | ~46 | 6 | 90 s | 180 s |
| Register (alias "ana") | 54 | 6 | 90 s | 180 s |
| BridgeOpen, Spanish mobile | 20 | 3 | 45 s | 90 s |
| Admin: switch, status, list | 13 | 2 | 30 s | 60 s |
| Admin: accept #n | 14 | 2 | 30 s | 60 s |
| AdminReply: new requests | 15 | 2 | 30 s | 60 s |

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
