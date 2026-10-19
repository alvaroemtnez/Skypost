# Crypto — protocol v1

Implemented in `core/crates/keryx/src/crypto.rs` (primitives, key schedule)
and `keyring.rs` (identities, links, replay windows). Rationale:
[0015](../decisions/0015-identities.md). v0 (group key + hash ratchets,
[0007](../decisions/0007-crypto.md)) is retired.

## Threat model

- Protect integrity and authenticity of every message, and the content of
  encrypted ones, against anyone listening on HF; reject spoofed and replayed
  frames.
- Two-party chats: each side knows exactly who sent a message.
- Groups: members trust each other; a member could forge another member's
  group messages.
- Out of scope: traffic analysis and direction finding (HF is observable and
  the callsign must be sent in clear anyway), a compromised device used live,
  and forward secrecy (see Limits).

## Identities

- X25519 key pair per station. `secret` is 32 random bytes (clamped by X25519).
- Fingerprint: `SHA-256("keryx-v1/id" ‖ public)`. Shown to users as the first
  10 bytes in hex groups of four characters for manual verification.
- Short id (on air, 4 bits): `fingerprint[0] mod 15`, so 0..=14. 15 is
  broadcast.

## Links

A link is what a message is sealed for: a pair of identities or a group.

| Link | Root key (HKDF-Extract, salt `"keryx-v1"`) |
|---|---|
| Pair A–B | ikm = `X25519(a, B)` ‖ min(A, B) ‖ max(A, B) (public keys compared bytewise) |
| Group | ikm = `"group"` ‖ group key (32 random bytes) |

An all-zero X25519 output (low-order public key) is rejected.

Group fingerprint: `SHA-256("keryx-v1/group" ‖ group key)`; group short id =
`fingerprint[0] mod 15`.

## Key schedule (HKDF-SHA256, HMAC-SHA256)

| Value | Derivation |
|---|---|
| Sender key of station S on a link | HKDF-Expand(root, `"send"` ‖ S's public key) → 32 B |
| Message key, counter n | `mk = HMAC(sender key, n as u16 BE)` |
| enc ‖ mac | HKDF-Expand(prk = mk, `"keys"`) → 64 B |
| ACK key of the acknowledging station A | HKDF-Expand(root, `"ack"` ‖ A's public key) → 32 B |

Each (link, sender, counter) gives one message key, used once. Keys are
stateless: no ratchet to keep in step.

## Sealing

1. Header with the sender's short id, the destination short id and the next
   send counter of this (link, sender).
2. If encrypted: ChaCha20 (RFC 8439), key = enc, nonce = header (4 B) ‖ 8 zero
   bytes, over the body.
3. Tag = HMAC-SHA256(mac, header ‖ body as sent)[0..8].
4. Advance the counter and persist **before** transmitting.

Unauthenticated frames (`Discover`, `Announce`) carry no tag.

## Opening

1. Parse the header. Candidate links: pair links with a contact or Pharos
   whose short id is `src` (if `dst` is ours or 15), and group links whose
   short id is `dst` with roster members whose short id is `src`.
   `Register` and `GroupJoin` carry the sender's public key in the body, which
   names the candidate directly.
2. For each candidate: check the replay window, derive the key, verify the
   tag in constant time. First match wins; none → `AuthFailed`.
3. Replay window per (link, sender): highest counter seen plus a 64-bit
   bitmap. Older than the window → `Replay`. Seen and verifying → `Duplicate`
   (the sender missed our ACK: re-ACK, do not deliver again).
4. Only after the tag verifies: update the window, then decrypt.

A forged frame cannot change any state.

## Compact frames

- ACK tag: HMAC-SHA256(ACK key, ACK bytes 0..5 ‖ full acknowledged counter
  BE)[0..4]. Binding the full counter stops replay of old ACKs for other
  counters with the same low byte.
- Compact Poll tag: HMAC-SHA256(mac of the poll's message key, bytes 0..5)[0..4].

## Backups

`backup::seal(passphrase, plaintext)`: Argon2id (m = 19 MiB, t = 2, p = 1,
16-byte random salt) → 32 B; HKDF-Expand(`"backup"`) → enc ‖ mac; ChaCha20
(zero nonce, fresh key per salt) and HMAC-SHA256 (32 B) over magic ‖ salt ‖
ciphertext. Layout: `SKYPOSTB1` ‖ salt ‖ ciphertext ‖ tag. The plaintext is
the keyring export (identity, contacts, groups, Pharos list, send counters)
plus the app's settings.

On restore every send counter jumps by **4096**, so a restored phone never
reuses a message key it used after the backup was taken (unless it sent more
than 4096 messages to one link since then).

## Properties and limits

- 256-bit keys; X25519 gives ~128-bit security.
- 8-byte tags: forgery chance 2⁻⁶⁴ per attempt. ACK and Poll tags are 4 bytes
  (2⁻³²): a forged ACK only shows a false tick, a forged poll only wastes
  Pharos airtime.
- **No forward secrecy:** the identity secret re-derives every pair key. Keep
  it in the Android Keystore; rotate the identity after a loss.
- Group members can read and forge each other's group messages.
- Counter exhaustion (65 536 messages per link and sender) requires a new
  identity or group key.
- One identity must never run on two devices at once (counter reuse).
- State files hold key material: 0600 on Linux, Keystore-encrypted on Android.
