# Roadmap

Tick items as they land. Keep this file current; it is how a new session
(human or Claude) finds the next task.

## Phase 0 — real NVIS data with off-the-shelf parts

Done in the repo:
- [x] `keryx` CLI: keygen, provision, encode/decode, ACKs, band, lane
- [x] Python glue: JS8Call API ↔ `keryx` ↔ Matrix/ntfy, JSONL logs
- [x] Drop Matrix/ntfy; prototype forwards through `signal-cli` ([0014](decisions/0014-bridges-sidecars.md))
- [x] End-to-end test against a fake JS8Call

To do:
- [x] Regenerate golden vectors for the `keryx-v0` salt (rename, decision 0011) and check only key-dependent bytes moved
- [ ] Revisit the lane set (five lanes may be too many) and the logic between them; name them after
- [ ] Verify the JS8Call 3.x API (port, message types, `TDRIFT`) and fix `js8api.py`
- [ ] Bench test: two radios into attenuators/dummy loads, signed and encrypted
- [ ] Choose dial frequencies (see `spec/band-plan.md`)
- [ ] First on-air tests (signed plaintext only), base at home, field trips
- [ ] Collect logs per band and hour; summarise them in `docs/propagation-log.md`
- [ ] Ask URE the open regulatory points

## Protocol v1 — identities, Pharos, bridged chats

Design: decisions [0015](decisions/0015-identities.md), [0016](decisions/0016-pharos-registration-and-bridging.md),
[0017](decisions/0017-chat-app-ui.md); specs `crypto.md`, `identity.md`, `frame-format.md`, `pharos.md`.

- [x] Design and specs
- [ ] `keryx`: identities, keyring (pair and group links, replay windows), v1 header, new types, compact Poll and ACK, `skypost:` URIs, backups; v1 golden vectors
- [ ] `keryx` CLI and Phase 0 prototype on identities
- [ ] `pharos`: registry (allow list, aliases, approval), store and forward with listen windows, handles
- [ ] `pharos`: bridge bot router (alias prompts, `@alias`, sticky target, limits)
- [ ] Android: chat UI on fakes (onboarding, chat list, chat, new chat, Pharos, settings, my QR, `skypost:` deep links)
- [ ] Optional ephemeral rekey for forward secrecy in encrypted mode

## Phase 1 — real PHY and base daemon

- [ ] Confirm JS8 frame payload size and frame-type bits in the JS8Call-improved engine; freeze `JS8_FRAME_PAYLOAD_BYTES`
- [ ] `keryx-js8` crate: FFI to the JS8Call-improved C++ core (`cxx`), binary frames in/out, multi-speed decode
- [ ] Callsign identification in binary mode (Art. 29)
- [ ] `pharos`: PHY task, wideband SDR input, all lanes in parallel, persist session + outbox
- [ ] `Bridge` trait in `pharos` (send, stream incoming); SOS goes to every bridge
- [ ] Signal bridge: JSON-RPC client to `signal-cli daemon --socket`; aarch64 libsignal on the Pi
- [ ] WhatsApp bridge: `wacli`; verify `sync --follow --events` carries message content (else a `whatsmeow` sidecar)
- [ ] MeshCore bridge: `sidecars/meshcore/` on `meshcore_py`, JSON lines over stdio
- [ ] systemd units for pharos and the sidecars; update routine for signal-cli
- [ ] Channel-simulator tests (codec2 `ch`, Watterson fading) in CI
- [x] Decide tag length (8 vs 16): 8, decision 0015
- [ ] Text codec v1: word dictionary, weights trained on Phase 0 messages
- [ ] Band plan from measured data

## Phase 2 — Android app

- [x] First build; script-based toolchain (`android/build.sh`, [0012](decisions/0012-android-script-build.md))
- [x] Bump to current stable (AGP 9.4, Kotlin 2.4, Gradle 9.8, SDK 37); R8, Navigation 3, splash, icon, theme, backup rules
- [ ] Material 3 Expressive once material3 1.5 is stable
- [ ] Try the debug APK on a phone
- [ ] `keryx-ffi` crate (UniFFI) + cargo-ndk build into `jniLibs`
- [ ] Radio foreground service: USB permission, CAT (usb-serial-for-android), USB audio routing
- [ ] GNSS time and position
- [ ] QR provisioning, Keystore-encrypted state, PIN for advanced mode
- [ ] Delivery ticks wired to ARQ events; inbox

## Phase 3 — fast lane

- [ ] Mercury over its TCP interface for G90-class stations
- [ ] Test QMX+ SSB with OFDM

## Phase 4 — voice

- [ ] Voice notes: codec2 as data over the fast lane; bridge to Signal/WhatsApp voice notes
- [ ] Live encrypted voice (codec2 + stream cipher), if still wanted
