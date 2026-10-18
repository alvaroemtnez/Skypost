# Crypto — protocol v0

Implemented in `core/crates/keryx/src/crypto.rs`. Rationale:
[decision 0007](../decisions/0007-crypto.md).

## Threat model

- Protect content and integrity of family messages against anyone listening
  on HF, and spoofed or replayed messages.
- Members of a family group trust each other (shared group key).
- A lost or stolen phone must not reveal messages sent before the loss.
- Out of scope: traffic analysis and direction finding (HF is inherently
  observable), a compromised device used live.

## Provisioning

- `keryx keygen` → `keryx:v0:t<8|16>:<BASE32(32 random bytes)>`. This string
  is the QR payload. Hand it over in person.
- Each station runs `provision(group_key, me, tag_len)`, which derives every
  sender chain and the ACK key, then forgets the group key.
- Member ids: 0..=14 (0 = base). 15 = broadcast.

## Derivations (HKDF-SHA256, HMAC-SHA256)

| Value | Derivation |
|---|---|
| Chain key, sender `s`, step 0 | HKDF(salt = `"keryx-v0"`, ikm = group key, info = `"chain"` ‖ s) → 32 B |
| ACK key | HKDF(salt = `"keryx-v0"`, ikm = group key, info = `"ack"`) → 32 B |
| Ratchet step | `mk = HMAC(ck, 0x01)`, `ck' = HMAC(ck, 0x02)` |
| Message keys | HKDF-Expand(prk = mk, info = `"keys"`) → 64 B: enc = [0..32], mac = [32..64] |

Message key `n` of a sender is the `mk` produced at step `n`. Each `mk` is used
for exactly one message.

## Sealing

1. Header with `src = me`, `counter = n` (next send counter).
2. If encrypted: ChaCha20 (RFC 8439), key = enc, nonce = header (4 B) ‖ 8 zero
   bytes, initial block counter 0, over the body.
3. Tag = HMAC-SHA256(mac, header ‖ body as sent), truncated to `tag_len`.
4. Advance the send chain; persist state **before** transmitting.

Counter exhaustion (65 536 messages per sender) requires re-provisioning.

## Opening

1. Parse header; reject own frames (`FromSelf`) and unknown senders.
2. Find `mk` for (src, counter) **without changing state**:
   - counter < next: use the stored skipped key if present, else `Replay`
     (or `Duplicate` if the exact frame was delivered recently).
   - counter ≥ next: ratchet forward, storing skipped keys; at most
     `MAX_SKIP` = 512 steps ahead (`CounterJump`); keep at most
     `MAX_STORED_SKIPPED` = 64 skipped keys per sender.
3. Verify the tag in constant time. Only on success: commit the new chain
   state, record (src, counter, tag) in a 32-entry recent list, decrypt.

A forged frame therefore cannot desynchronise a receiver.

## Signed plaintext mode

Same keys and tag; the body is not encrypted (`enc` bit = 0). Integrity and
authenticity without hiding meaning: the legal default on air.

## Properties and limits

- 256-bit symmetric keys: ample margin, including against quantum attacks.
- Forward secrecy against device loss: past chain keys are gone.
- No post-compromise security: whoever gets current chain keys reads the future
  until the group is re-provisioned. Re-provision after any loss.
- A group member can read and forge any sender's messages (group trust).
- 8-byte tags: forgery chance 2⁻⁶⁴ per attempt; HF gives an attacker very few
  attempts per hour. 16 bytes costs ~1 more JS8 frame. Pending decision.
- ACKs use a static key and 4-byte tags: replayable, low impact.
- Losing the state file means re-provisioning with a **new** group key;
  restoring an old state would reuse message keys.
- The state file holds key material: 0600 on Linux, Android Keystore-encrypted
  app storage on the phone.
