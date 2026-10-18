# 0007. Pre-shared group key, per-sender hash ratchets, ChaCha20 + HMAC-SHA256

Status: accepted (2026-10-18)

## Context

Strong encryption with minimal bytes on air; no key exchange over HF; forward secrecy against a lost phone.

## Decision

QR-provisioned 256-bit group key; each station derives every sender's chain and drops the group key; per-message keys from hash ratchets; ChaCha20 + HMAC-SHA256 encrypt-then-MAC, tag 8 bytes (16 optional); signed-plaintext mode with the same keys. See `spec/crypto.md`.

## Consequences

- Zero key-exchange bytes on air; 12 bytes overhead per message (4 header + 8 tag).
- No post-compromise security: re-provision after any loss.
- Group members can forge each other's messages (group trust).
