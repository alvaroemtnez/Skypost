# android/

Kotlin + Jetpack Compose Material 3. Package `io.alvaroedo.skypost`, min SDK 26.

- Never built yet: first sync will need version bumps (`gradle/libs.versions.toml`).
- UI: `ui/family` (big-button family mode), `ui/advanced` (operator settings).
- `core/KeryxCore.kt` is the boundary to the Rust core; `FakeKeryxCore` until the
  UniFFI bindings exist. Do not implement protocol logic in Kotlin.
- `radio/RadioLink.kt` documents the USB audio/CAT plan; `FakeRadioLink` until then.
- Strings are Spanish in `res/values/strings.xml`. Family mode must stay
  usable by a non-technical person under stress: big targets, few choices,
  confirm before SOS, plain words.
- See `android/README.md` for the phase 2 plan.
