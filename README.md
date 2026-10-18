# Skypost

Encrypted off-grid messaging over HF NVIS for a family: phone + small HF radio
in the field, a 24/7 base station at home that bridges to Matrix, Signal,
WhatsApp, MeshCore and ntfy. No third-party infrastructure.

- Robust lane: the JS8 waveform (decodes down to about −24 dB) carrying our
  own compact binary frames. Works on a QDX.
- Fast lane (later): Mercury OFDM on SSB radios like the G90.
- Group crypto: QR-provisioned key, per-sender ratchets, ChaCha20 +
  HMAC-SHA256. Signed plaintext by default on air; encryption for emergencies.

Status: Phase 0. The protocol core and CLI work and are tested; the base
daemon and Android app are skeletons. See [docs/design.md](docs/design.md)
and [docs/roadmap.md](docs/roadmap.md).

## Quick start

```sh
make test                                   # Rust + Python tests
make keryx                                    # builds core/target/release/keryx
N=core/target/release/keryx
$N keygen > group.code                      # secret: the QR payload
$N provision --code @group.code --me 1 --state field.json
$N provision --code @group.code --me 0 --state base.json
$N encode --state field.json text "Hemos acampado" | xargs $N decode --state base.json
```

Phase 0 on-air testing with JS8Call: [prototype/README.md](prototype/README.md).

## Layout

```
core/       Rust: keryx (protocol), keryx-cli (`keryx`), pharos (base)
android/    Kotlin + Compose Material 3 app
prototype/  Phase 0 Python glue for JS8Call
docs/       design, specs, decisions, regulatory and research notes
```

## Licence

GPL-3.0-or-later (it will link GPL code: JS8Call's engine and Mercury).
