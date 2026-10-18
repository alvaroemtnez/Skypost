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
- Edge-to-edge is on: pad with window insets.
- Backups and device transfer stay disabled (session state = key material).
- `./build.sh test` must stay at zero lint warnings.
- UI: `ui/family` (big-button family mode), `ui/advanced` (operator settings).
- `core/KeryxCore.kt` is the boundary to the Rust core; `FakeKeryxCore` until the
  UniFFI bindings exist. Do not implement protocol logic in Kotlin.
- `radio/RadioLink.kt` documents the USB audio/CAT plan; `FakeRadioLink` until then.
- Strings are Spanish in `res/values/strings.xml`. Family mode must stay
  usable by a non-technical person under stress: big targets, few choices,
  confirm before SOS, plain words.
- See `android/README.md` for the phase 2 plan.
