# Skypost

Names (`docs/decisions/0011-naming.md`): **Skypost** = project and app,
**Keryx** = wire protocol, **Pharos** = base station.

Encrypted off-grid family messaging over HF NVIS (40/60/80 m). A field kit
(Android phone + QDX or G90 over USB OTG) talks to another kit, or to a 24/7
base station (Raspberry Pi) that bridges to Matrix (→ Signal/WhatsApp),
MeshCore and ntfy. Owner: Álvaro, licensed ham, Spain. UI language: Spanish.

Read `docs/design.md` first. Then `docs/roadmap.md` for what is next.

## Layout

- `core/` — Rust workspace. `keryx` (wire protocol, no I/O), `keryx-cli`
  (binary `keryx`), `pharos` (base station, skeleton). Golden vectors in
  `core/testvectors/`.
- `android/` — Kotlin + Compose Material 3 app. Skeleton, never built yet.
- `prototype/` — Phase 0 Python glue: JS8Call API ↔ `keryx` ↔ Matrix/ntfy.
- `docs/` — design, specs (`docs/spec/`), decisions (`docs/decisions/`),
  regulatory notes, research notes with sources.

## Commands

```sh
make test          # Rust tests + clippy + Python tests
make keryx           # release build of the keryx CLI
cd core && cargo test -p keryx
cd prototype && python3 -m pytest -q
cd android && ./gradlew assembleDebug     # needs the Android SDK
```

## Rules

- **Never commit or push.** Leave changes in the working tree; the owner
  reviews and commits everything by hand.
- **One protocol implementation.** All protocol logic lives in `keryx`.
  Kotlin and Python call it (UniFFI / the `keryx` CLI); never re-implement
  codec, crypto, ARQ or lane choice elsewhere.
- **Wire format changes** (anything that changes bytes on air: layouts, type
  values, codec weights, phrase order, KDF labels) need: an updated
  `docs/spec/` page, regenerated vectors
  (`KERYX_REGEN=1 cargo test -p keryx --test vectors`), and a version bump
  unless old and new still interoperate. Never regenerate vectors to make a
  failing test pass without understanding why the bytes changed.
- **Append only:** `MsgType` values and `canned::PHRASES`.
- **Signed plaintext is the default on air.** Encryption is an explicit
  emergency/dummy-load mode (Spanish rules; `docs/regulatory-es.md`). Do not
  make encryption the default anywhere.
- **Persist session state before transmitting** (counters must never repeat).
  State files hold key material: 0600, never logged, never committed.
- **Crypto state only changes after a tag verifies.**
- Decisions with lasting impact get a short record in `docs/decisions/`.
  Keep `docs/roadmap.md` ticked.
- Code, comments and docs in English; user-facing app strings in Spanish.

## Unverified assumptions (check before building on them)

- JS8 frame payload is 9 bytes (72 bits) and JS8 frame-type bits can mark
  first/last segments (`JS8_FRAME_PAYLOAD_BYTES`).
- JS8Call 3.x API shape used by `prototype/skypost_p0/js8api.py`.
- Android Gradle versions in `android/gradle/libs.versions.toml`.
- The band schedule in `keryx::bandplan` is a guess until Phase 0 data.
