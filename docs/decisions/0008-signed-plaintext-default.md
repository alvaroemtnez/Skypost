# 0008. Signed plaintext by default; encryption is explicit

Status: accepted (2026-10-18)

## Context

Spanish rules forbid coding transmissions to hide their meaning; the ITU distress provision allows any means in distress. The user wants encryption available when safety matters.

## Decision

Default mode: plaintext body + authentication tag + callsign in clear. Encrypted mode is a toggle for emergencies and for dummy-load tests; tools refuse to encrypt without an explicit flag. The base mirrors the member's mode.

## Consequences

- Legal everyday operation with integrity and anti-spoofing.
- Emergency switch with one control.
- Open question: whether compressed plaintext counts as hiding meaning.
