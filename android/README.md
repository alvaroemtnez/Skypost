# Android app

Kotlin + Jetpack Compose, Material 3 (dynamic colour on Android 12+).
Package `io.alvaroedo.skypost` (debug builds: `io.alvaroedo.skypost.debug`, so
both install side by side). Min SDK 26, target and compile SDK 37.

**Status: skeleton.** It builds (`./build.sh`); the UI runs on fakes for the
core and the radio. Not yet tried on a phone.

## Building

Everything goes through `build.sh`; Android Studio is not needed. It runs on
any x86_64 Linux, including immutable distros like Fedora Silverblue, and only
needs `bash`, `curl`, `tar`, `unzip` and `sha256sum` from the system.

The first run downloads the toolchain into `android/.tools/` (about 2 GB,
ignored by git):

- [Eclipse Temurin JDK 21](https://adoptium.net/), checked against the
  SHA-256 from the Adoptium API.
- The Android SDK command line tools (version and SHA-256 pinned in
  `build.sh`). Through their `android sdk` CLI (which replaces `sdkmanager`;
  always run with `--no-metrics`) it then installs `platform-tools`, and the
  `platforms` and `build-tools` packages named by `compileSdk` and
  `buildToolsVersion` in `app/build.gradle.kts`. Installing them accepts the
  [Android SDK terms](https://developer.android.com/studio/terms).
- Gradle itself (the wrapper checks `distributionSha256Sum`) and its caches.

`build.sh` always uses these copies and ignores any Java, Android SDK or
`~/.gradle` on the system. It also points `ANDROID_USER_HOME` at
`.tools/android-user/`, so AGP's debug keystore and the Android CLI's files
stay out of `~/.android`. The one exception is adb: it always keeps its key in
`~/.android/adbkey`. That is the key your phone authorises, shared by every
project, so it doesn't need re-authorising. Everything else goes with
`./build.sh distclean` (or deleting the clone).

```console
$ ./build.sh            # debug APK -> dist/skypost-debug.apk
$ ./build.sh install    # build and install on the phone connected over USB (adb)
$ ./build.sh run        # install, launch, and follow the app's logcat
$ ./build.sh logcat     # follow the log of the already running app
$ ./build.sh test       # JVM unit tests + Android lint (lint warnings fail too)
$ ./build.sh release    # release APK (see below)
$ ./build.sh gradle tasks --all   # any Gradle task, with the bundled toolchain
$ ./build.sh setup      # only download the toolchain
$ ./build.sh clean      # remove build outputs
$ ./build.sh distclean  # also remove .tools/
```

From the repo root, `make apk` and `make apk-install` do the same as
`./build.sh` and `./build.sh install`.

A first build takes a few minutes (downloads included); after that a debug
build takes seconds. The debug APK is signed with an automatically generated
debug key (in `.tools/`), so it installs directly. After `distclean` that key
is new, and the phone refuses to update the old debug install: `build.sh`
prints the `adb uninstall` to run (which wipes the debug app's data).

Release builds are shrunk and optimised by R8 (`proguard-rules.pro`; about
1.3 MB today against 11 MB for debug). Test them before handing a phone to
the family: R8 can break code that uses reflection, such as JNA later on.

### Phone setup

Enable developer options (tap *Build number* seven times), then turn on *USB
debugging*. On the first `install`, accept the computer's key on the phone.
`.tools/android-sdk/platform-tools/adb devices` should list the phone. The
same USB-C port is used for the radio later, so install over USB, or pair
*Wireless debugging* once with `adb pair` / `adb connect` and keep the cable
free for the rig.

### Using the tools directly

To run `./gradlew`, `adb`, `java` or `keytool` by hand in a shell:

```console
$ eval "$(./build.sh env)"
$ adb devices
$ ./gradlew :app:dependencies
```

`build.sh` also writes `local.properties` (ignored by git) with the SDK path,
for editor tooling and plain `./gradlew` runs.

### Release builds

A release APK must be signed with your own key. Create the keystore once with
the bundled JDK and keep it outside the repository. Updates only install over a
previous version when they are signed with the same key, so back it up.

```console
$ .tools/jdk-21/bin/keytool -genkeypair -v -keystore ~/skypost.jks -alias skypost \
      -keyalg RSA -keysize 4096 -validity 10000
$ SKYPOST_KEYSTORE=~/skypost.jks SKYPOST_KEY_ALIAS=skypost ./build.sh release
```

This prints `dist/skypost-release.apk`. apksigner prompts for the passwords
unless `SKYPOST_KEYSTORE_PASS` and `SKYPOST_KEY_PASS` are exported (e.g. in
CI). Without `SKYPOST_KEYSTORE`, you get `dist/skypost-release-unsigned.apk`.

### Updating the toolchain

- SDK platform / build tools: change `compileSdk` / `buildToolsVersion` in
  `app/build.gradle.kts`; the next build installs them. From API 37 the
  platform packages are named with a minor version (`platforms/android-37.0`),
  which `build.sh` handles. Raising `targetSdk` opts into that release's
  behaviour changes: read Google's list before doing it.
- Latest versions: `.tools/android-sdk/cmdline-tools/latest/bin/android
  --no-metrics sdk list --all` for SDK packages;
  `https://dl.google.com/android/maven2/<group path>/<artifact>/maven-metadata.xml`
  for AndroidX and AGP.
- Command line tools: set `CMDLINE_TOOLS_VERSION` and `CMDLINE_TOOLS_SHA256`
  in `build.sh` (version list and SHA-1s in
  `https://dl.google.com/android/repository/repository2-3.xml`), then
  `./build.sh distclean`.
- JDK: Temurin 21 is re-downloaded only after `distclean`, which picks the
  latest 21.x.
- Gradle: `distributionUrl` and `distributionSha256Sum` in
  `gradle/wrapper/gradle-wrapper.properties` (the checksum is at the
  distribution URL + `.sha256`).
- Libraries and plugins: `gradle/libs.versions.toml`. `kotlin` must stay a
  version the Compose and serialization compiler plugins exist for (they
  share Kotlin's version number). Lint flags outdated ones (`GradleDependency`).

## What exists

- Family screen: four big buttons (Estoy bien, Necesito ayuda with a confirm
  step, Mensaje, Recibidos), a status strip (radio, band, GPS time), and the
  outgoing list with delivery states. Long-press the status strip for advanced.
- Advanced screen: the emergency-encryption toggle, placeholders for the rest.
- `KeryxCore` and `RadioLink` interfaces with fakes, so the UI runs without a
  radio or the Rust core. `FamilyViewModelTest` covers the UI-side rules
  (signed plaintext by default, encryption only once switched on).

## App foundations

- **Stack:** AGP 9 (built-in Kotlin), Kotlin 2.4, Compose BOM, Material 3
  1.4, Navigation 3, versions in `gradle/libs.versions.toml`. Java/Kotlin
  bytecode target 17.
- **Navigation:** Navigation 3 (`MainActivity.kt`). Destinations are
  `@Serializable` `NavKey` objects, the back stack survives rotation and
  process death, and predictive back works. `FamilyViewModel` is
  activity-scoped because both screens share it; new screens get their own
  ViewModel per back-stack entry.
- **Theme** (`ui/theme`): dynamic colour on Android 12+. Before that,
  `Color.kt`, a full Material 3 scheme (light and dark, all roles) generated
  from the seed `#2563C9` by `scripts/gen_colors.py` (same algorithm as
  Material Theme Builder). Change the seed there and regenerate; don't edit
  `Color.kt` by hand. Material 3 *Expressive* is internal in the stable
  material3 1.4 and public only in the 1.5 pre-releases. Switch to
  `MaterialExpressiveTheme` in `Theme.kt` once 1.5 is stable.
- **Edge-to-edge** (required since target 35): content is drawn under the
  system bars, so pad with insets (`statusBarsPadding()`, `Scaffold`'s
  padding) rather than assuming they're out of the way.
- **Launch:** adaptive launcher icon with a monochrome layer for themed icons
  (`drawable/ic_launcher_foreground.xml`, background `@color/icon_background`)
  and the splash screen API (`Theme.Skypost.Starting`, same look on 8-11).
- **Icons:** Material Symbols, copied as vector drawables (`ic_arrow_back.xml`).
  The `material-icons-extended` library has been frozen since Compose 1.7.
  Take new icons from fonts.google.com/icons (Android format).
- **No backup, no transfer:** `allowBackup="false"`, `data_extraction_rules.xml`
  and `backup_rules.xml` keep every app file off Google's cloud backup and off
  device-to-device transfer. The session state holds key material, and a
  restored copy would repeat counters. Keep it that way
  ([0013](../docs/decisions/0013-no-app-backup.md)).
- **Strings:** all user-facing text in `res/values/strings.xml` (Spanish).
  Only Spanish resources are packaged (`localeFilters`).
- **Release:** R8 minify and resource shrinking; keep rules in
  `proguard-rules.pro`.

## Phase 2 plan

1. `core/crates/keryx-ffi`: a thin UniFFI crate over `keryx` (+ the JS8 engine
   later). Build with `cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release`
   and generate Kotlin bindings with `uniffi-bindgen`.
2. Replace `FakeKeryxCore` with the generated bindings.
3. `RadioService`: foreground service (type `connectedDevice`) owning USB
   permission, usb-serial-for-android for CAT/PTT, `AudioRecord`/`AudioTrack`
   routed to the USB audio device with the `UNPROCESSED` input preset.
4. GNSS: position for status messages; UTC from the GNSS fix for JS8 slot timing.
5. Provisioning by QR (the `keryx:v0:t8:<key>` code from `keryx keygen`);
   keep the session state in app-private storage, encrypted with an Android
   Keystore key.
6. PIN for the advanced screen.

## Editor note

There is no need for Android Studio. VSCodium with a Kotlin extension edits
the code fine (no Compose previews: use `./build.sh run` on a phone instead),
and Claude Code works from the repo root. If you do want Studio for a layout
preview or the profiler, open `android/` after running `./build.sh setup`; it
picks up the SDK from `local.properties`.
