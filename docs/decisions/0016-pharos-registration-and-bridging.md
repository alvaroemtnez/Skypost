# 0016. Pharos as an identity: trust list, discovery, registration, bridged chats

Status: accepted (2026-10-19); access rules refined by [0019](0019-pharos-access.md). Builds on [0014](0014-bridges-sidecars.md) and
[0015](0015-identities.md).

## Context

Pharos must work like any other off-grid identity, but the app keeps Pharos
stations apart from contacts. Field users need to find a Pharos in range,
trust it without being spoofed, and chat through it with Signal, WhatsApp and
MeshCore contacts. People on those networks need a simple way to reach a
given field user through the Pharos number.

## Decision

- A Pharos has an X25519 identity, a name and its callsign. The app keeps a
  separate **trusted Pharos** list: added by QR (best, done at home) or by
  discovery (trust on first use, fingerprint shown for later checking).
- **Discovery:** an unauthenticated 4-byte `Discover` frame, always on the most
  robust lane. Every Pharos in range answers with an `Announce` (public key,
  flags, name) after a random delay and audio offset. Rate limited on Pharos.
- **Registration:** the client sends `Register` (its public key and alias,
  tagged with the pair key, which proves both keys). Pharos admits it if the
  key is on its allow list, or after the operator approves on Signal, and
  answers with a `Notice`. Aliases are unique per Pharos.
- **Ping** is a one-frame compact `Poll` that also says how long the client
  will keep listening. Its ACK says whether messages are waiting. Pings and
  their ACKs use the most robust lane.
- **Store and forward:** Pharos sends straight away while the client is inside
  its listen window, else holds messages until it hears the client again.
- **Bridged chats** use one-byte handles per (client, Pharos): `BridgeOpen`
  binds a handle to a network address once; `Bridged` messages then cost two
  bytes of overhead. Delivery ticks stop at Pharos: no read receipts or
  typing state cross the bridge.
- **The Pharos bot** routes messages from bridged contacts by alias: prompts
  when the target is unclear, `@alias` to switch, a one-hour sticky target,
  mandatory `@alias` when several field users are talking to the same contact.
  One-to-one only (Signal/WhatsApp groups are ignored). Details:
  `spec/pharos.md`.

## Consequences

- One code path for contacts and Pharos in `keryx`; the split is UI only.
- Discovery costs ~1 + 5 frames per Pharos answering; registration ~6 frames,
  once. Adding a Pharos by QR at home costs nothing on air.
- Inbound traffic from strangers is off by default (allow list, or contacts
  the field user wrote to first): HF airtime is scarce and the operator is
  responsible for what the station relays.
- Relaying third-party traffic is limited in Spain outside emergencies (Orden
  IET/1311/2013 Art. 30.3). Bridging stays an open regulatory point.
