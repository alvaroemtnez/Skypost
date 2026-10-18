# Roadmap

Tick items as they land. Keep this file current; it is how a new session
(human or Claude) finds the next task.

## Phase 0 — real NVIS data with off-the-shelf parts

Done in the repo:
- [x] `nvl` CLI: keygen, provision, encode/decode, ACKs, band, lane
- [x] Python glue: JS8Call API ↔ `nvl` ↔ Matrix/ntfy, JSONL logs
- [x] End-to-end test against a fake JS8Call

To do:
- [ ] Verify the JS8Call 3.x API (port, message types, `TDRIFT`) and fix `js8api.py`
- [ ] Bench test: two radios into attenuators/dummy loads, signed and encrypted
- [ ] Choose dial frequencies (see `spec/band-plan.md`)
- [ ] First on-air tests (signed plaintext only), base at home, field trips
- [ ] Collect logs per band and hour; summarise them in `docs/propagation-log.md`
- [ ] Ask URE the open regulatory points

## Phase 1 — real PHY and base daemon

- [ ] Confirm JS8 frame payload size and frame-type bits in the JS8Call-improved engine; freeze `JS8_FRAME_PAYLOAD_BYTES`
- [ ] `nvl-js8` crate: FFI to the JS8Call-improved C++ core (`cxx`), binary frames in/out, multi-speed decode
- [ ] Callsign identification in binary mode (Art. 29)
- [ ] `nvl-daemon`: PHY task, wideband SDR input, all lanes in parallel, persist session + outbox
- [ ] Matrix bridge (and ntfy, MeshCore companion protocol)
- [ ] Channel-simulator tests (codec2 `ch`, Watterson fading) in CI
- [ ] Decide tag length (8 vs 16)
- [ ] Text codec v1: word dictionary, weights trained on Phase 0 messages
- [ ] Band plan from measured data

## Phase 2 — Android app

- [ ] First Gradle sync and build; bump versions
- [ ] `nvl-ffi` crate (UniFFI) + cargo-ndk build into `jniLibs`
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
