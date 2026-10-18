#!/usr/bin/env bash
# build.sh - Build the Skypost Android app from the command line, no Android Studio needed.
#
# Usage: ./build.sh [command]   (default: debug)
#
#   debug       build the debug APK -> dist/skypost-debug.apk
#   release     build the release APK, signed if SKYPOST_KEYSTORE is set
#   install     build the debug APK and install it on the connected phone
#   run         install, then launch the app and follow its log
#   logcat      follow the log of the running app
#   test        run the JVM unit tests and Android lint
#   gradle ...  run any Gradle task with the bundled toolchain
#   env         print exports for using gradlew/adb directly: eval "$(./build.sh env)"
#   setup       only download the toolchain into .tools/
#   clean       remove build outputs
#   distclean   also remove .tools/ (the downloaded toolchain and Gradle caches)
#
# Everything (JDK, Android SDK, Gradle and its caches) is downloaded into
# android/.tools/; nothing is installed system-wide. See android/README.md.

set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$ANDROID_DIR/dist"
TOOLS_DIR="$ANDROID_DIR/.tools"
APP_GRADLE="$ANDROID_DIR/app/build.gradle.kts"

# AGP 8.x needs JDK 17+; 21 is the current LTS and runs Gradle 8.14.
JDK_VERSION=21
# https://developer.android.com/studio#command-line-tools-only
CMDLINE_TOOLS_VERSION=16111833
CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8

JDK_DIR="$TOOLS_DIR/jdk-$JDK_VERSION"
SDK_DIR="$TOOLS_DIR/android-sdk"
ADB="$SDK_DIR/platform-tools/adb"

# Make every tool use the copies in .tools/, regardless of what the system has.
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
unset ANDROID_SDK_ROOT
export GRADLE_USER_HOME="$TOOLS_DIR/gradle"
export PATH="$JDK_DIR/bin:$SDK_DIR/platform-tools:$PATH"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

require() {
    local cmd
    for cmd in "$@"; do
        command -v "$cmd" >/dev/null || die "'$cmd' not found, please install it."
    done
}

# Read a value from app/build.gradle.kts, e.g. gradle_value compileSdk -> 35.
gradle_value() {
    local value
    value="$(sed -n "s/^ *$1 *= *\"\{0,1\}\([0-9.]*\)\"\{0,1\}.*/\1/p" "$APP_GRADLE" | head -n 1)"
    [ -n "$value" ] || die "Could not read $1 from $APP_GRADLE."
    echo "$value"
}

# Download $1 to $2 and check it against the SHA-256 $3.
fetch_verified() {
    local url="$1" out="$2" sha="$3"
    curl -fL --retry 3 --progress-bar "$url" -o "$out"
    echo "$sha  $out" | sha256sum -c --quiet - || die "Checksum mismatch for $url"
}

setup_jdk() {
    [ -x "$JDK_DIR/bin/java" ] && return
    local tmp meta url sha
    tmp="$(mktemp -d)"
    # The assets API gives the download link together with its SHA-256.
    meta="$(curl -fsSL "https://api.adoptium.net/v3/assets/latest/$JDK_VERSION/hotspot?os=linux&architecture=x64&image_type=jdk&vendor=eclipse")"
    url="$(echo "$meta" | sed -n 's/.*"link": *"\([^"]*\.tar\.gz\)".*/\1/p' | head -n 1)"
    sha="$(echo "$meta" | sed -n 's/.*"checksum": *"\([0-9a-f]\{64\}\)".*/\1/p' | head -n 1)"
    [ -n "$url" ] && [ -n "$sha" ] || die "Could not find a Temurin JDK $JDK_VERSION download."
    info "Downloading Temurin JDK $JDK_VERSION ($(basename "$url"))"
    fetch_verified "$url" "$tmp/jdk.tar.gz" "$sha"
    mkdir -p "$tmp/out" "$TOOLS_DIR"
    tar -xf "$tmp/jdk.tar.gz" -C "$tmp/out" --strip-components=1
    mv "$tmp/out" "$JDK_DIR"
    rm -rf "$tmp"
}

setup_sdk() {
    local sdkmanager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$sdkmanager" ]; then
        info "Downloading Android command line tools"
        local tmp
        tmp="$(mktemp -d)"
        fetch_verified "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_SHA256"
        unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
        mkdir -p "$SDK_DIR/cmdline-tools"
        mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
        rm -rf "$tmp"
    fi

    # The SDK packages follow app/build.gradle.kts, so bumping compileSdk or
    # buildToolsVersion there is enough; the next build installs them.
    local api build_tools
    api="$(gradle_value compileSdk)"
    build_tools="$(gradle_value buildToolsVersion)"
    if [ ! -d "$SDK_DIR/platforms/android-$api" ] || [ ! -d "$SDK_DIR/build-tools/$build_tools" ] \
        || [ ! -d "$SDK_DIR/platform-tools" ]; then
        info "Accepting Android SDK licenses"
        yes | "$sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null || true
        info "Installing platform-tools, platforms;android-$api and build-tools;$build_tools"
        "$sdkmanager" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-$api" "build-tools;$build_tools"
    fi

    # For running ./gradlew by hand and for editor tooling; ignored by git.
    echo "sdk.dir=$SDK_DIR" > "$ANDROID_DIR/local.properties"
}

setup() {
    [ "$(uname -s)" = Linux ] || die "Only Linux is supported."
    # Google ships the SDK build tools (aapt2, d8...) for x86_64 Linux only.
    [ "$(uname -m)" = x86_64 ] || die "Unsupported architecture $(uname -m), the Android SDK needs x86_64."
    require curl tar unzip sha256sum
    setup_jdk
    setup_sdk
}

gradle() {
    (cd "$ANDROID_DIR" && ./gradlew --no-daemon "$@")
}

application_id() {
    sed -n 's/^ *applicationId *= *"\(.*\)".*/\1/p' "$APP_GRADLE"
}

build_debug() {
    setup
    info "Building debug APK"
    gradle assembleDebug
    mkdir -p "$DIST_DIR"
    cp "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" "$DIST_DIR/skypost-debug.apk"
    info "Done: $DIST_DIR/skypost-debug.apk"
}

build_release() {
    setup
    info "Building release APK"
    gradle assembleRelease

    local unsigned="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
    mkdir -p "$DIST_DIR"
    if [ -z "${SKYPOST_KEYSTORE:-}" ]; then
        cp "$unsigned" "$DIST_DIR/skypost-release-unsigned.apk"
        info "SKYPOST_KEYSTORE not set, APK left unsigned: $DIST_DIR/skypost-release-unsigned.apk"
        return
    fi

    local args=(sign --ks "$SKYPOST_KEYSTORE" --out "$DIST_DIR/skypost-release.apk")
    [ -n "${SKYPOST_KEY_ALIAS:-}" ] && args+=(--ks-key-alias "$SKYPOST_KEY_ALIAS")
    # Passwords are read from the environment if set, otherwise apksigner asks for them.
    [ -n "${SKYPOST_KEYSTORE_PASS:-}" ] && args+=(--ks-pass env:SKYPOST_KEYSTORE_PASS)
    [ -n "${SKYPOST_KEY_PASS:-}" ] && args+=(--key-pass env:SKYPOST_KEY_PASS)

    info "Signing release APK with $SKYPOST_KEYSTORE"
    "$SDK_DIR/build-tools/$(gradle_value buildToolsVersion)/apksigner" "${args[@]}" "$unsigned"
    info "Done: $DIST_DIR/skypost-release.apk"
}

install_debug() {
    build_debug
    info "Installing on connected device"
    "$ADB" install -r "$DIST_DIR/skypost-debug.apk"
}

logcat() {
    local pid
    pid="$("$ADB" shell pidof -s "$(application_id)" || true)"
    [ -n "$pid" ] || die "$(application_id) is not running."
    "$ADB" logcat --pid="$pid"
}

run_app() {
    install_debug
    info "Launching $(application_id)"
    "$ADB" shell am start -W -n "$(application_id)/.MainActivity" >/dev/null
    logcat
}

check() {
    setup
    gradle testDebugUnitTest lintDebug
}

print_env() {
    setup >&2
    printf 'export JAVA_HOME=%q ANDROID_HOME=%q GRADLE_USER_HOME=%q\n' "$JAVA_HOME" "$ANDROID_HOME" "$GRADLE_USER_HOME"
    printf 'export PATH=%q:"$PATH"\n' "$JDK_DIR/bin:$SDK_DIR/platform-tools"
    printf 'unset ANDROID_SDK_ROOT\n'
}

# Remove build outputs.
clean() {
    rm -rf "$ANDROID_DIR/build" "$ANDROID_DIR/app/build" "$ANDROID_DIR/.gradle" "$ANDROID_DIR/.kotlin" "$DIST_DIR"
}

# Remove build outputs and the downloaded toolchain.
distclean() {
    clean
    rm -rf "$TOOLS_DIR" "$ANDROID_DIR/local.properties"
}

case "${1:-debug}" in
    debug) build_debug ;;
    release) build_release ;;
    install) install_debug ;;
    run) run_app ;;
    logcat) logcat ;;
    test) check ;;
    gradle) shift; setup; gradle "$@" ;;
    env) print_env ;;
    setup) setup ;;
    clean) clean ;;
    distclean) distclean ;;
    -h|--help|help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) die "Unknown command '$1'. Run ./build.sh help." ;;
esac
