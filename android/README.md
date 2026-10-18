# Android app

Kotlin + Jetpack Compose, Material 3 (dynamic colour on Android 12+).
Min SDK 26. Package `es.nvislink`.

**Status: skeleton, never built.** It was written where Google's Maven
repository was unreachable, so the first job is a Gradle sync on your machine:

```sh
cd android
./gradlew assembleDebug        # needs JDK 17+ and the Android SDK (ANDROID_HOME)
```

Expect to bump the versions in `gradle/libs.versions.toml` (pinned to late-2024
releases) and fix whatever the sync reports.

## What exists

- Family screen: four big buttons (Estoy bien, Necesito ayuda with a confirm
  step, Mensaje, Recibidos), a status strip (radio, band, GPS time), and the
  outgoing list with delivery states. Long-press the status strip for advanced.
- Advanced screen: the emergency-encryption toggle, placeholders for the rest.
- `NvlCore` and `RadioLink` interfaces with fakes, so the UI runs without a
  radio or the Rust core.

## Phase 2 plan

1. `core/crates/nvl-ffi`: a thin UniFFI crate over `nvl-proto` (+ the JS8 engine
   later). Build with `cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release`
   and generate Kotlin bindings with `uniffi-bindgen`.
2. Replace `FakeNvlCore` with the generated bindings.
3. `RadioService`: foreground service (type `connectedDevice`) owning USB
   permission, usb-serial-for-android for CAT/PTT, `AudioRecord`/`AudioTrack`
   routed to the USB audio device with the `UNPROCESSED` input preset.
4. GNSS: position for status messages; UTC from the GNSS fix for JS8 slot timing.
5. Provisioning by QR (the `nvislink:v0:t8:<key>` code from `nvl keygen`);
   keep the session state in app-private storage, encrypted with an Android
   Keystore key.
6. PIN for the advanced screen.

## Editor note

VSCodium's Kotlin support is weaker than Android Studio's for Android work
(no layout previews, slower completion). Claude Code works fine from the repo
root either way; open `android/` in Android Studio when you want previews or
the device tools.
