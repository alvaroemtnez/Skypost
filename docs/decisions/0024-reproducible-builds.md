# 0024. Reproducible Android builds, signed as Gradle builds them

Status: accepted (2026-11-12)

## Context

F-Droid builds every app from source. With reproducible builds it can ship the
developer's own signed APK instead of one signed with its key: it builds the same
commit, copies the developer's signature onto its build and checks it verifies.
Then an F-Droid install and a direct (Forgejo, Obtainium) install update each
other. This can only be switched on when an app is first included; after that its
APKs carry F-Droid's signature for good.

Two things in a usual release build break the check even when every file is
identical (found in HFPredict and MeshConnect, November 2026):

- zipalign, and apksigner's default realignment, rewrite the zip's alignment
  padding, so the signed APK is not Gradle's APK plus a signature;
- AGP adds a "dependency metadata" block to the APK signing block, encrypted with
  a key only Google can read.

## Decision

- `android/build.sh release` signs Gradle's output as it is:
  `apksigner sign --alignment-preserved true`, no zipalign. Gradle already aligns
  the APK.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }` in
  `android/app/build.gradle.kts`.
- Skypost goes to F-Droid with reproducible builds from its first submission
  (`AllowedAPKSigningKeys:` and `Binaries:` in the recipe).

## Consequences

- Checked at `d3c6693` (release build, R8 on): F-Droid's build and ours signed with a
  throwaway key pass F-Droid's `verify_apks`. Recheck before submitting and after any
  change to how the app is built, notably the Rust core (docs/releasing.md).
- Anything that makes the release build depend on the machine (absolute paths in
  native code, timestamps, unpinned toolchains) now breaks the release, not only a
  nicety: pin and remap (docs/releasing.md, "When the Rust core lands").
