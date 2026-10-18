# core/ (Rust workspace)

- `crates/nvl-proto`: the protocol. Pure, no I/O, no async. Every module has
  unit tests; `tests/vectors.rs` pins the wire format to `testvectors/v0.json`.
- `crates/nvl-cli`: the `nvl` binary. Stdout is machine-readable (base32, hex
  or JSON); errors on stderr. Exit codes: 0 ok, 1 I/O or other error,
  2 protocol error (or a usage error from clap), 3 duplicate frame (caller
  should re-ACK). The Phase 0 Python depends on 0 and 3.
- `crates/nvl-daemon`: base station. `station.rs` is synchronous logic
  (frames in, `Effect`s out) and is where behaviour gets tested; `main.rs` is
  the async shell. Radio side and bridges are TODO (phase 1).

Before finishing a change: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`.

Planned crates: `nvl-js8` (FFI to the JS8Call-improved C++ engine, via
`cxx`), `nvl-ffi` (UniFFI bindings for Android).

Crypto crates are the RustCrypto 2026 majors (`hmac` 0.13, `sha2` 0.11,
`hkdf` 0.13, `chacha20` 0.10). Their APIs differ from older examples online.
