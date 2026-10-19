# 0015. Per-station identities with pairwise keys; no signatures on air (protocol v1)

Status: accepted (2026-10-19). Supersedes [0007](0007-crypto.md) for key
management; the message crypto (ChaCha20 + truncated HMAC-SHA256) stays.

## Context

v0 used one pre-shared group key per family and member ids 0..14 handed out by
an admin. We want what MeshCore and Reticulum have: every station owns a
cryptographic identity, contacts are added by scanning a QR code, a friendly
name rides along, and both encrypted and signed-plaintext messages prove who
sent them.

Airtime decides the mechanism. A JS8 frame carries ~9 bytes and takes 6–30 s.
An Ed25519 signature is 64 bytes: 7–8 extra frames, about +105 s per message
at Normal speed, which more than doubles a typical 6-frame text. Public
keys (32 B) cannot ride in every frame either.

## Decision

- **Identity:** one X25519 key pair per station (phone or Pharos), with a
  user-chosen alias and an optional callsign. Contacts are exchanged as
  `skypost:` URIs (QR, clipboard, deep link); see `spec/identity.md`.
- **Pairwise keys:** X25519 between two identities gives a shared root key.
  Each direction derives per-message keys from (root, sender, counter). The
  8-byte HMAC tag proves the sender to the receiver, which is all a two-party
  chat needs. No signatures on air.
- **Groups:** a random 256-bit group key, shared by QR. Messages carry the same
  8-byte tag under a key derived from the group key and the sender. This
  proves "a group member sent it", not which member: any member could forge
  another member's group messages. A per-message signature would close that at
  +64 bytes; not worth it for family groups.
- **Addressing:** the 4-byte header stays. `src`/`dst` become 4-bit short ids
  derived from a hash of the public key (0..14; 15 = broadcast). Collisions
  are resolved by trying each candidate key: the tag is the authority.
- **Stateless message keys** with a 64-message replay window replace the v0
  hash ratchets. Simpler, no skipped-key storage, no desynchronisation.
- **Tag length** fixed at 8 bytes (closes the 8-vs-16 question).
- Wire format version 1.

## Consequences

- Same airtime as v0 for every message type; a ping fits one JS8 frame.
- Shield (signed) and padlock (encrypted) both mean "verified sender" in
  one-to-one chats; in groups, "verified group member".
- **No forward secrecy.** The long-term identity key re-derives every pair
  key, so whoever extracts it from a device, and recorded the traffic, can
  read past encrypted messages. v0's ratchets gave forward secrecy against a
  lost phone. Accepted because signed plaintext is the everyday mode and
  encrypted mode is for emergencies; mitigated by the Android Keystore. An
  optional on-air ephemeral rekey (32 B each way) can restore it later.
- Backups can carry the identity. Restoring jumps every send counter by 4096
  so message keys are not reused; never run one identity on two phones.
- Recipients can forge messages "from" a sender to themselves (MACs are
  deniable). Irrelevant between family members.
