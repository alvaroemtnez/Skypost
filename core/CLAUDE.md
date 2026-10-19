# core/ (Rust workspace)

- `crates/keryx`: the protocol (v1). Pure, no I/O, no async (randomness via
  `getrandom` is the one exception). `keyring.rs` holds identities, links,
  counters and replay windows; `message.rs` seals/opens whole frames. Every
  module has unit tests; `tests/vectors.rs` pins the wire format to
  `testvectors/v1.json`.
- `crates/keryx-cli`: the `keryx` binary. Stdout is machine-readable (base32, hex
  or JSON); errors on stderr. Exit codes: 0 ok, 1 I/O or other error,
  2 protocol error (or a usage error from clap), 3 duplicate frame (caller
  should re-ACK). The Phase 0 Python depends on 0 and 3.
- `crates/pharos`: base station. `admin.rs` parses and runs admin commands. `station.rs` is synchronous logic
  (frames and ticks in, `Effect`s out: registration, store and forward,
  handles) and is where behaviour gets tested; `registry.rs` and `outbox.rs`
  are its persisted state; `main.rs` is the async shell (`pharos init`,
  `identity`, `run`). `router.rs` is the bridge bot (pure logic). Radio
  side and bridge sidecars are TODO.

Before finishing a change: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`.

Planned crates: `keryx-js8` (FFI to the JS8Call-improved C++ engine, via
`cxx`), `keryx-ffi` (UniFFI bindings for Android).

Crypto crates are the RustCrypto 2026 majors (`hmac` 0.13, `sha2` 0.11,
`hkdf` 0.13, `chacha20` 0.10). Their APIs differ from older examples online.
