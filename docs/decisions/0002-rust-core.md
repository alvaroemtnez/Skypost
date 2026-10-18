# 0002. Rust for the shared core and the base daemon

Status: accepted (2026-10-18)

## Context

The protocol must run inside the Android app and on the Pi. It parses untrusted input from the air. The DSP engines we reuse (JS8 engine, Mercury, codec2) are C/C++.

## Decision

`keryx` (no I/O) holds framing, codec, crypto, ARQ, lane choice, band plan. `pharos` and `keryx-cli` build on it. C/C++ engines sit behind one narrow FFI boundary (`cxx` / `bindgen`).

## Consequences

- One implementation of the protocol; app and base cannot drift.
- Memory-safe parsing; RustCrypto crates.
- Builds for Android (cargo-ndk) and aarch64 Linux.
- Cost: a C++ boundary to the JS8 engine.
- Rejected: C++ core (unsafe parsing), Go (poor on Android, protocol twice), Python (cannot run in the app).
