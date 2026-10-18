# 0012. Script-based Android build, no Android Studio

Status: accepted (2026-10-18)

## Context

Android Studio is heavy, the owner does not want it, and the dev machine runs an
immutable distro where system-wide SDK and JDK installs are awkward. The app
should still build in a reproducible way, on that machine and in CI.

## Decision

`android/build.sh` is the only supported way to build the app. It downloads a
pinned toolchain into `android/.tools/` (git-ignored): Temurin JDK 21, the
Android command line tools, and the SDK packages named in
`app/build.gradle.kts`, plus Gradle and its caches through the wrapper. Every
download is checked: the JDK against Adoptium's SHA-256, the command line tools
against a SHA-256 pinned in the script, Gradle against `distributionSha256Sum`.
SDK packages come through Google's `android sdk` CLI, which replaced
`sdkmanager`; it always runs with `--no-metrics`. (It fetches from Google's
repository over HTTPS; whether it checks package checksums like `sdkmanager`
did is not documented.)
The script ignores any system JDK or SDK and points `ANDROID_USER_HOME` into
`.tools/`.
The approach follows the owner's earlier gpx2fit-android project.

## Consequences

- A fresh clone builds with `bash`, `curl`, `tar`, `unzip` and `sha256sum`,
  and `distclean` undoes it all. Each clone uses about 2 GB.
- x86_64 Linux only: Google ships the SDK build tools only for that platform.
- adb keeps its key in `~/.android/adbkey` regardless of `ANDROID_USER_HOME`.
  That is the only file outside the clone, and it is the one the phone trusts.
- Phase 2 adds the NDK to the same SDK for cargo-ndk, as one more SDK
  package (`sdk_package ndk/<version>`) in `setup_sdk`.
- Android Studio still works on `android/` (via `local.properties`) but is not
  required, and nothing may depend on it.
