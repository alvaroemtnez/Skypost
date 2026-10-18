# 0003. Kotlin + Jetpack Compose Material 3 for Android

Status: accepted (2026-10-18)

## Context

Material Design 3 is a requirement. The app needs USB host, USB audio routing, serial CAT, GNSS and a foreground service.

## Decision

Native Kotlin with Compose Material 3 (dynamic colour). Rust core reached through UniFFI-generated Kotlin bindings. No Flutter / React Native / web wrapper.

## Consequences

- Compose M3 is Google's reference implementation of MD3.
- Platform APIs are first-class.
- Cross-platform UI frameworks would still need the same native code for USB, audio and services.
- Linux desktop later via Compose Multiplatform, keeping MD3.
