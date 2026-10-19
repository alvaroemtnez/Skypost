# Skypost

Names (`docs/decisions/0011-naming.md`): **Skypost** = project and app,
**Keryx** = wire protocol, **Pharos** = base station.

Encrypted off-grid family messaging over HF NVIS (40/60/80 m). A field kit
(Android phone + QDX or G90 over USB OTG) talks to another kit, or to a 24/7
base station (Raspberry Pi) that bridges to Signal, WhatsApp and MeshCore
through upstream sidecars (`docs/decisions/0014-bridges-sidecars.md`).
Owner: Álvaro, licensed ham, Spain. UI language: Spanish.

Read `docs/design.md` first. Then `docs/roadmap.md` for what is next.

## Layout

- `core/` — Rust workspace. `keryx` (wire protocol, no I/O), `keryx-cli`
  (binary `keryx`), `pharos` (base station, skeleton). Golden vectors in
  `core/testvectors/`.
- `android/` — Kotlin + Compose Material 3 app. Skeleton on fakes; builds with
  `android/build.sh` (self-contained toolchain, no Android Studio).
- `prototype/` — Phase 0 Python glue: JS8Call API ↔ `keryx` ↔ signal-cli.
- `docs/` — design, specs (`docs/spec/`), decisions (`docs/decisions/`),
  regulatory notes, research notes with sources.

## Commands

```sh
make test          # Rust tests + clippy + Python tests
make keryx           # release build of the keryx CLI
cd core && cargo test -p keryx
cd prototype && python3 -m pytest -q
make apk           # android/build.sh: fetches JDK + SDK into android/.tools/
android/build.sh test      # Android unit tests + lint (help: android/build.sh help)
```

## Rules

- **Commit freely, never push.** Commit on your own judgement in small,
  coherent commits with clear messages (work on `main` unless a branch is
  useful). The owner reviews the history and does all pushing. Never
  `git push`, force-push, or rewrite already-pushed history.
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
- The band schedule in `keryx::bandplan` is a guess until Phase 0 data.
