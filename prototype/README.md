# Phase 0 prototype

Goal: real NVIS data in weeks, with no modem work. Unmodified JS8Call does the
radio; the Rust `nvl` CLI does every protocol step (codec, crypto, ACKs), so
this prototype already speaks the real v0 wire format. Python only glues
JS8Call's API to `nvl` and forwards to Matrix/ntfy.

Throwaway by design: Phase 1 replaces it with `nvl-daemon` and binary JS8 frames.

## How it goes on air

`EA5BAS NV0 <base32 of the wire frame>` as a JS8 directed message. JS8Call
splits it into frames itself. Base32 costs an estimated 20–30 % more airtime than raw
binary frames will in Phase 1; fine for collecting data.

## Setup

```sh
cd core && cargo build --release -p nvl-cli          # builds core/target/release/nvl
core/target/release/nvl keygen > group.code          # once; keep it secret (alias nvl=core/target/release/nvl)
nvl provision --code @group.code --me 0 --state ~/.local/share/nvislink/base.json   # on the base
nvl provision --code @group.code --me 1 --state ~/.local/share/nvislink/field.json  # on the laptop
cp prototype/config.example.toml prototype/base.toml   # and field.toml; edit both
```

In JS8Call: enable the TCP API (Settings > Reporting), set your callsign, pick
the band from `nvl band --month M --hour H` (UTC), and choose the speed by hand.

## Run

```sh
cd prototype
NVL_MATRIX_TOKEN=... python3 -m nvl_p0.base --config base.toml       # on the Pi
python3 -m nvl_p0.field --config field.toml text "Hemos acampado"   # in the field
python3 -m nvl_p0.field --config field.toml ok --lat 42.63 --lon 0.66
```

On air, signed plaintext only. `--encrypt` refuses to run unless you also pass
`--dummy-load` (bench tests) or `--emergency`. See `docs/regulatory-es.md`.

## What to collect

Every event goes to the JSONL log (`[log] path`): time, band, SNR heard by
each side, clock offset, attempts, delivered or failed. Keep these files; they
replace the guessed table in `nvl_proto::bandplan` and tune the lane margins.

## Unverified assumptions

- JS8Call 3.x API: port 2442, `TX.SEND_MESSAGE`, `RX.DIRECTED` with `FROM`,
  `TO`, `TEXT`, `SNR`, `TDRIFT`. Check `js8api.py` against the current API.

## Tests

```sh
python3 -m pytest -q   # includes an end-to-end run against a fake JS8Call
```
