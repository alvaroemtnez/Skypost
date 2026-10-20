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
- [x] `keryx`: identities, keyring (pair and group links, replay windows), v1 header, new types, compact Poll and ACK, `skypost:` URIs, backups; v1 golden vectors
- [x] `keryx` CLI and Phase 0 prototype on identities
- [x] `pharos`: registry (allow list, aliases, approval), store and forward with listen windows, handles
- [x] `pharos`: bridge bot router (alias prompts, `@alias`, sticky target, limits); wiring to sidecars is Phase 1
- [x] Android: chat UI on fakes (onboarding, chat list, chat, new chat, Pharos, settings, my QR, `skypost:` deep links)
- [x] Pharos access: modes, access-code proof, admin commands, bot feedback ([0019](decisions/0019-pharos-access.md))
- [x] Remote management: `/admin`, admin password (encrypted only on air), `admins`/`revocar`, welcome and `/ayuda` on Signal/WhatsApp, short MeshCore replies ([0020](decisions/0020-remote-management.md))
- [x] Pharos access v2: switches (open, requests, login, emergency, stealth) in the state file, access requests from radio and bridges, binary `Admin`/`AdminReply` on air, any admin removable ([0021](decisions/0021-pharos-access-v2.md))
- [ ] Revisit the emergency-mode reminder and how chatty automatic notices are (decision 0021 keeps them minimal)
- [ ] Revisit forward secrecy for encrypted mode (ephemeral rekey, ~32 B each way) and the whole key model

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
- [ ] Pharos clock without internet: GNSS receiver (also for JS8 timing) or a clock-sync command like MeshCore's; queue expiry, lockouts and request expiry depend on it
- [ ] `pharos` control socket so `pharos set`/`pharos admin` work while the service runs (today: stop it first)
- [ ] Remote reboot admin command: decide what it restarts (the `pharos` service, the sidecars, or the whole Pi via a narrow sudoers/polkit rule) and how the station is sure to come back (hardware watchdog, radio and bridges re-attached on boot)
- [ ] Channel-simulator tests (codec2 `ch`, Watterson fading) in CI
- [x] Decide tag length (8 vs 16): 8, decision 0015
- [ ] Text codec v1: word dictionary, weights trained on Phase 0 messages
- [ ] Band plan from measured data

## Phase 2 — Android app

- [x] First build; script-based toolchain (`android/build.sh`, [0012](decisions/0012-android-script-build.md))
- [x] Bump to current stable (AGP 9.4, Kotlin 2.4, Gradle 9.8, SDK 37); R8, Navigation 3, splash, icon, theme, backup rules
- [ ] Material 3 Expressive once material3 1.5 is stable
- [x] Try the debug APK on a phone
- [ ] `keryx-ffi` crate (UniFFI) + cargo-ndk build into `jniLibs`
- [ ] Radio foreground service: USB permission, CAT (usb-serial-for-android), USB audio routing; drive the top-bar radio indicator (grey / orange audio only / green audio + CAT)
- [ ] Audio-only radios (no CAT): make setting the frequency by hand easy (show band and dial frequency, check it, remind on band changes)
- [x] GNSS position for Estoy bien, positions and SOS (`location/Locator.kt`, platform LocationManager, no Google services)
- [ ] GNSS time for JS8 slot sync
- [ ] QR provisioning, Keystore-encrypted state, PIN for advanced mode
- [ ] Real `Repository` over the keyring (Keystore-encrypted state); delivery ticks wired to ARQ events
- [x] Try the chat UI on a phone (first round of feedback applied: in-app QR scanner, slide-back, SOS button and contacts, global encryption, signal colours, English/Spanish/Valencian)
- [x] Second feedback round: message bar, archive/pin/unread/select/swipe, blocking (core + app), contact pings, translated stock phrases, MeshCore links
- [x] Third feedback round: restore from the welcome screen fixed, contact QR, verified contacts (in-app scan = verified, pasted/linked = not), Pharos management screen, SOS held 3 s
- [x] Pharos management v2: switches, pending-requests popup, add/remove admins, replies in the app's language, personal-data warning in plain, "Request access" / "I have a code" in discovery
- [ ] Android notification for admins when a Pharos reports new access requests
- [ ] Screenshot tests

## Releases

- [ ] Forgejo Actions release flow: on a pushed tag `vX.Y.Z`, build the APK
  (`android/build.sh release`, signing key from a repository secret) and the
  Pharos assets (`pharos` and `keryx` for aarch64 and x86_64, example config,
  systemd units), then create a Forgejo release named after the tag whose notes
  are that version's section of `CHANGELOG.md` (start the file; Keep a
  Changelog format)

## Phase 3 — fast lane

- [ ] Mercury over its TCP interface for G90-class stations
- [ ] Test QMX+ SSB with OFDM

## Phase 4 — voice

- [ ] Voice notes: codec2 as data over the fast lane; bridge to Signal/WhatsApp voice notes
- [ ] Live encrypted voice (codec2 + stream cipher), if still wanted
