# android/

Kotlin + Jetpack Compose Material 3. Package `io.alvaroedo.skypost`, min SDK 26.

- Build only through `./build.sh` (JDK + SDK in `.tools/`, see README). Do not
  rely on a system JDK/SDK or `~/.gradle`; use `./build.sh gradle <task>` or
  `eval "$(./build.sh env)"` for anything custom. `./build.sh test` = unit tests + lint.
- SDK packages follow `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts`.
  The SDK CLI is `android sdk` (not sdkmanager), always with `--no-metrics`.
- AGP 9 built-in Kotlin: do not apply `org.jetbrains.kotlin.android`; use the
  top-level `kotlin { compilerOptions { } }` block, not `kotlinOptions`.
- Stable releases only in `libs.versions.toml` (no alpha/beta/rc).
- Navigation 3: add a `@Serializable data object X : NavKey` and an `entry<X>`
  in `MainActivity.kt`.
- Colours: never hard-code; use `MaterialTheme.colorScheme`. The pre-12
  palette is generated (`scripts/gen_colors.py`), do not edit `Color.kt`.
- Icons: Material Symbols vector drawables in `res/drawable`, not material-icons.
  Add them to `scripts/gen_icons.py` and run it; lint fails on unused ones.
- Edge-to-edge is on: pad with window insets.
- Backups and device transfer stay disabled (session state = key material).
- `./build.sh test` must stay at zero lint warnings.
- UI (decision 0017): `ui/onboarding`, `ui/chats`, `ui/chat`, `ui/newchat`,
  `ui/pharos`, `ui/identity`, `ui/settings`, shared bits in `ui/components`.
  Each screen takes its ViewModel; `AppViewModel` holds the identity gate and
  pending imports.
- `data/Repository.kt` is all the UI talks to; `FakeRepository` until phase 2.
- `core/KeryxCore.kt` is the boundary to the Rust core; `FakeKeryxCore` until the
  UniFFI bindings exist. Do not implement protocol logic in Kotlin (the fakes'
  rough URI parsing and estimates are test doubles, not the real thing).
- `radio/RadioLink.kt` documents the USB audio/CAT plan; `FakeRadioLink` until then.
- Strings are Spanish in `res/values/strings.xml`. SOS, quick messages and
  the chat must stay usable by a non-technical person under stress: big
  targets, few choices, confirm before SOS, plain words. SOS is always signed
  plaintext; encryption is opt-in per chat with a warning.
- See `android/README.md` for the phase 2 plan.
