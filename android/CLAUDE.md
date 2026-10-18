# android/

Kotlin + Jetpack Compose Material 3. Package `io.alvaroedo.skypost`, min SDK 26.

- Build only through `./build.sh` (JDK + SDK in `.tools/`, see README). Do not
  rely on a system JDK/SDK or `~/.gradle`; use `./build.sh gradle <task>` or
  `eval "$(./build.sh env)"` for anything custom. `./build.sh test` = unit tests + lint.
- SDK packages follow `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts`.
- Versions in `gradle/libs.versions.toml` are late 2024 and still need bumping.
- UI: `ui/family` (big-button family mode), `ui/advanced` (operator settings).
- `core/KeryxCore.kt` is the boundary to the Rust core; `FakeKeryxCore` until the
  UniFFI bindings exist. Do not implement protocol logic in Kotlin.
- `radio/RadioLink.kt` documents the USB audio/CAT plan; `FakeRadioLink` until then.
- Strings are Spanish in `res/values/strings.xml`. Family mode must stay
  usable by a non-technical person under stress: big targets, few choices,
  confirm before SOS, plain words.
- See `android/README.md` for the phase 2 plan.
