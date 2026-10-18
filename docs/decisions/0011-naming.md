# 0011. Names: Skypost, Keryx, Pharos

Status: accepted (2026-10-18)

## Context

The working name "nvislink" described the propagation mode, not the product, and
read poorly for non-hams. "Mercury" (messenger of the gods) was the first idea,
but Mercury is already an HF data modem we use as the fast lane, and its parent
project is HERMES; reusing either would suggest an affiliation.

## Decision

- **Skypost** — the project and the app (post sent through the sky). App id
  `io.alvaroedo.skypost`. An unrelated Bluesky tool shares the name; accepted.
- **Keryx** (κῆρυξ, herald) — the wire protocol. Crate `keryx`, CLI
  `keryx-cli` (binary `keryx`), HKDF salt `"keryx-v0"`, key code `keryx:v0:`.
- **Pharos** (lighthouse) — the base station daemon, crate `pharos`.
- Lane/sub-protocol names wait until the lane set itself is settled.

The salt and key-code prefix changed without a version bump: nothing had been
transmitted or provisioned with v0, so there is nothing to interoperate with.

## Consequences

- Golden vectors must be regenerated for the new salt; only key-dependent
  bytes (sealed bodies, tags, ACK tags) should change.
- Group codes made with the old `nvl keygen` are invalid; re-run `keryx keygen`.
