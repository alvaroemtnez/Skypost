# Releasing

Skypost has no release yet (Phase 0). This page holds what the app stores need, worked out
and tested in November 2026 alongside HFPredict and MeshConnect, so the first release
starts from it. The release flow itself (a tag building and publishing the APK and the
Pharos assets) is still open: [roadmap.md](roadmap.md), "Releases".

## The signing key

Android installs an update only if it is signed with the same key as the installed app, so
the key is permanent: lose it and every user has to uninstall and reinstall. Make it once,
for Skypost alone (not shared with HFPredict or MeshConnect), keep two offline copies, and
never commit it.

```sh
eval "$(android/build.sh env)"
keytool -genkeypair -v -keystore skypost-release.jks -storetype PKCS12 \
  -alias skypost -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Álvaro Edo Martínez, O=Skypost"
keytool -list -v -keystore skypost-release.jks -alias skypost | grep SHA256
```

Sign a release with it:

```sh
SKYPOST_KEYSTORE=~/keys/skypost-release.jks SKYPOST_KEY_ALIAS=skypost android/build.sh release
```

Publish the certificate's SHA-256 (in a `docs/verifying.md`, as the other apps do) so users,
Obtainium and F-Droid can pin it.

## Reproducible builds

`android/build.sh release` signs Gradle's output exactly as it is
(`apksigner sign --alignment-preserved true`, no zipalign), and `dependenciesInfo` is off
in `android/app/build.gradle.kts`, so the APK's signing block holds only the signatures
([decision 0024](decisions/0024-reproducible-builds.md)). Then F-Droid can build the same
commit, copy our signature onto its build and check that it verifies, and ship our signed
APK: an F-Droid install and a Forgejo/Obtainium install then update each other.

Checked on 2026-11-12 at `d3c6693`, release build with R8: F-Droid's build (its
`fdroidserver:buildserver` container, `fdroid build --on-server`) and ours signed with a
throwaway key passed F-Droid's `verify_apks`; F-Droid's source scanner found nothing.
Run it again before submitting, and whenever the build changes shape (the Rust core,
below):

1. In a clone of <https://gitlab.com/fdroid/fdroiddata>, put the recipe in
   `metadata/io.alvaroedo.skypost.yml` with `commit:` set to the full hash.
2. In F-Droid's container (as in their Quick Start Guide; with podman add
   `--userns=keep-id`): `fdroid readmeta`, `fdroid rewritemeta io.alvaroedo.skypost`,
   `fdroid lint io.alvaroedo.skypost`, `fdroid build --verbose --on-server --no-tarball
   io.alvaroedo.skypost:<versionCode>`. If Gradle fails to start with "Could not create
   service of type FileAccessTimeJournal … For input string: \"\"", run it again: it happened
   intermittently in that container (twice in November 2026, both times fine on a rerun).
3. Build the same commit with `android/build.sh release` (a throwaway key is enough) and
   check with fdroidserver's `common.verify_apks(ours, fdroids_unsigned, tmpdir)`, or
   simply submit with `Binaries:` set and let `fdroid build` do it.

## F-Droid

- **When**: not before 18 January 2027. The inclusion policy wants three months of active
  development (Quality, point 6); the first commit is from 18 October 2026. In practice,
  when there is a release worth installing.
- **AI**: F-Droid has no generative-AI policy (November 2026); 49 of the first 54 commits
  were co-authored by an AI assistant. Whether to say so in the merge request is the
  author's call. IzzyOnDroid, the other F-Droid repository, does not take such apps.
- **Recipe**: `packaging/fdroid/io.alvaroedo.skypost.yml`, in the form `fdroid rewritemeta`
  writes (it drops comments, so the notes are here). F-Droid brings its own JDK and SDK and
  runs Gradle in `android/app`. With the first release, add `Binaries:` (the release APK's
  URL, `%v` for the version) and `AllowedAPKSigningKeys:` (the certificate's SHA-256,
  lowercase, no colons), and in fdroiddata set `commit:` to the tag's full hash
  (`git rev-parse vX.Y.Z^{commit}`; the merge request template refuses tags). Auto-update
  reads `versionName`/`versionCode` from `android/app/build.gradle.kts` at each `v*` tag, so
  bump both there for every release.
- **Store text**: `fastlane/metadata/android/<locale>/` (en-US, es-ES, ca): title, short
  description (under 80 characters), full description, and the icon
  (`en-US/images/icon.png`, from `docs/icon.png`). Still to add: phone screenshots
  (`en-US/images/phoneScreenshots/1.png` …) and `changelogs/<versionCode>.txt` per
  release (500 characters at most).
- **What reviewers may ask about**:
  - Network: none. The app has no `INTERNET` permission; it talks only to the radio over
    USB. No anti-feature about network services applies to the app.
  - Signal, WhatsApp and MeshCore appear in the app as destinations reached through a
    family member's Pharos base station, a separate program; the app never contacts those
    services. A reviewer might still consider the NonFreeNet anti-feature (it labels apps
    that "promote or depend entirely on a proprietary network service"); it would be a
    label, not a refusal.
  - Permissions: camera (QR codes), microphone (the radio's audio, through USB), location
    (position reports, from the platform LocationManager), a connected-device foreground
    service (the radio link), notifications.
  - Dependencies: all free, from Google Maven and Maven Central (AndroidX, Compose, CameraX,
    ZXing core, kotlinx). No Google Play services.
  - Transmitting requires an amateur radio licence; the store text says so.

## Accrescent

- Sign-up is closed (November 2026: only allowlisted GitHub accounts). When it opens:
  register, upload an APK set (`.apks`, from bundletool: Accrescent takes no plain APKs) and
  the 512 x 512 icon, and prove the app ID's domain with a DNS `TXT` record at
  `_accverify.alvaroedo.io` holding the code the reviewer emails.
- Requirements, checked against <https://accrescent.app/docs/guide/appendix/requirements/>:
  target SDK 37 (Accrescent follows Google Play's target SDK rules with no extensions);
  one signer, v2/v3 signatures, not a debug certificate; not debuggable; no self-updater;
  no root or ADB. Camera, location and microphone are on its list of permissions reviewed
  by hand: say what each is for (above). Accrescent has no generative-AI policy.
- To do before the first upload: a `build.sh accrescent` that builds the bundle and splits
  it with bundletool, signed with the release key (HFPredict's and MeshConnect's
  `build.sh` have one to adapt), and the same in the release workflow.

## When the Rust core lands (phase 2)

The app then carries `libkeryx_ffi.so` (cargo-ndk) and UniFFI's Kotlin bindings, which need
JNA. HFPredict went through the same; what it needed, to save the rediscovery:

- **F-Droid's build image has no C compiler**, and Rust needs one for cargo-ndk and the
  crates' build scripts: the recipe's `sudo:` step installs `build-essential`, and a
  `build:` step builds the core with F-Droid's NDK (`ndk:` in the recipe, matching
  `ndkVersion`) before Gradle runs.
- **Reproducible native code**: build the core with its paths remapped
  (`--remap-path-prefix`), so the `.so` is the same wherever the checkout lives, and pin
  the Rust toolchain (`rust-toolchain.toml`) and `Cargo.lock` (`--locked`).
- **Only ship ABIs the core is built for**: JNA's AAR carries `libjnidispatch.so` for x86,
  armeabi, mips and mips64 too. Left in, a device of that ABI installs the app and crashes
  without the core; exclude them with `packaging { jniLibs.excludes }`. Accrescent also
  wants every 32-bit ABI shipped with its 64-bit one.
- **JNA from Maven Central** carries a prebuilt native library: F-Droid accepts it (free
  licence, trusted repository), but expect the reviewer to ask.
