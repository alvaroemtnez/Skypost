# 0014. Bridges: Signal, WhatsApp and MeshCore as sidecar processes; no Matrix, no ntfy

Status: accepted (2026-10-19). Supersedes the Matrix/mautrix and ntfy bridges in `design.md`.

## Context

The base station bridged to Signal and WhatsApp through a Matrix homeserver
with mautrix bridges, plus ntfy and MeshCore. That is a homeserver, two
bridges and a push service to run 24/7 for a handful of family messages.
Writing our own Signal, WhatsApp or MeshCore clients is out of the question:
they need constant upkeep as the upstream protocols move. Every bridge must
build on a well-known, actively maintained project. State as of 2026-10-19:

| Target | Chosen | Alternatives looked at |
|---|---|---|
| MeshCore | `meshcore_py` (meshcore-dev, official reference; Python; serial/BLE/TCP) | `meshcore-cli` (official, built on `meshcore_py`, made for interactive and one-shot use); `meshcore-rs` (Rust port, one maintainer, 17 stars, 13 commands missing) |
| Signal | `signal-cli` (AsamK, ~5k stars, monthly releases; Java; JSON-RPC daemon) | `presage` (Rust, AGPL, git-only dependency, younger); `signal-cli-api` crate (a REST wrapper around signal-cli, one author, ~100 downloads) |
| WhatsApp | `wacli` (openclaw, ~2.8k stars, weekly releases; Go on `whatsmeow`; linux arm64 builds) | `vicentereig/whatsapp-cli` (one maintainer, no arm64 build); our own Go sidecar on `whatsmeow` |

## Decision

- Drop Matrix and ntfy entirely.
- Each bridge is a separate process supervised by systemd. Pharos talks to it
  over a narrow JSON interface and owns no messaging-protocol code:
  - Signal: `signal-cli daemon` with JSON-RPC on a Unix socket; Pharos is the
    JSON-RPC client (in Rust).
  - WhatsApp: `wacli` (`sync --follow --events` for incoming, `send text` for
    outgoing).
  - MeshCore: a small Python sidecar on `meshcore_py` (repo `sidecars/meshcore/`)
    holding the companion connection and speaking JSON lines on stdin/stdout.
    This is the one exception to [0010](0010-python-phase0-only.md).
- In Pharos every bridge sits behind one Rust trait (send a message, stream
  incoming ones), so a sidecar can be swapped without touching station logic.

## Consequences

- Nothing to run besides Pharos and three upstream programs; no homeserver.
- The Pi needs a JRE (25+) for signal-cli and its aarch64 `libsignal_jni.so`
  (prebuilt x86_64 only; aarch64 from exquo/signal-libs-build or self-built).
  signal-cli releases older than ~3 months may stop working: the base needs
  an update routine.
- WhatsApp through an unofficial client risks a ban: use a dedicated number,
  low volume. Meta's Cloud API does not fit (business account, 24 h window).
- Unverified: whether `wacli --events` carries incoming message content. If
  not, fall back to our own small Go sidecar on `whatsmeow`.
- Python stays in production, but only as one small isolated sidecar. Revisit
  `meshcore-rs` when it reaches parity and has more than one maintainer.
- No push notifications without a phone app on the other side: Signal and
  WhatsApp notifications replace ntfy, including SOS alerts.
