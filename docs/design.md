# Skypost — design

Skypost is the project and app, Keryx the wire protocol, Pharos the base
station (`decisions/0011-naming.md`).

Status: phase 0/1 groundwork. Last updated 2026-10-18.
Source of truth for the project. Detailed specs live in `docs/spec/`, decisions
in `docs/decisions/`, sources in `docs/research-notes.md`.

## 1. Goal

Reliable, encrypted, owned-infrastructure messaging between family members
when camping off-grid, in places with no phone signal and no line of sight to
the MeshCore LoRa community network (deep valleys, remote areas).

- No third-party infrastructure: no Starlink or satellite messengers.
- Reliability first: the JS8 experience of "it almost always decodes, even
  below the noise". Longer airtime is acceptable; minutes per message is not.
- Usable by a non-operator in an emergency: Álvaro's mum must be able to plug
  in a kit and press a button.

### Scenarios

| | Scenario | Who |
|---|---|---|
| A | Point to point between two field kits, fully off-grid. Short keyboard messages. | Licensed operator(s) |
| B | Field kit ↔ 24/7 base station that bridges to Signal/WhatsApp (via Matrix), the MeshCore room server and ntfy. | Normal use: licensed operator. Emergency: anyone. |

## 2. Decisions at a glance

| Topic | Decision | Record |
|---|---|---|
| Repo | One monorepo | [0001](decisions/0001-monorepo.md) |
| Core language | Rust, shared by app and base | [0002](decisions/0002-rust-core.md) |
| Android | Kotlin + Compose Material 3, no cross-platform UI framework | [0003](decisions/0003-kotlin-compose-m3.md) |
| Robust PHY | JS8 waveform carrying our own binary frames | [0004](decisions/0004-js8-waveform-binary-frames.md) |
| Fast PHY | Mercury (codec2 OFDM), later, G90-class radios only | [0005](decisions/0005-mercury-fast-lane.md) |
| Reticulum | Not used for the core | [0006](decisions/0006-no-reticulum-core.md) |
| Crypto | Pre-shared group key, per-sender hash ratchets, ChaCha20 + HMAC-SHA256 | [0007](decisions/0007-crypto.md) |
| On-air default | Signed plaintext; encryption only in emergencies or into a dummy load | [0008](decisions/0008-signed-plaintext-default.md) |
| Lane choice | Base decodes every lane; sender picks from the SNR in ACKs | [0009](decisions/0009-no-lane-negotiation.md) |
| Python | Phase 0 prototype only, protocol via the Rust CLI | [0010](decisions/0010-python-phase0-only.md) |

## 3. Propagation

NVIS (near-vertical incidence skywave) is the right tool for valleys: the
signal comes down almost vertically, so valley walls do not shadow it.

- **40 m by day, 80 m by night** is the core plan.
- **60 m** (Spain: 5351.5–5366.5 kHz, 15 W EIRP, any modulation) covers the
  dusk and dawn transitions, when 40 m starts skipping and 80 m is still
  absorbed.
- **Known gap:** on winter nights, especially toward solar minimum, the F2
  critical frequency can drop below 3.5 MHz before dawn. Then 80 m (and 60 m)
  stop reflecting vertically; only 160 m would work. The QDX has no 160 m; a
  QMX+ does. Treat pre-dawn winter hours as unreliable until measured.
- **Coverage:** NVIS is solid to roughly 300–400 km. Beyond that, 40 m
  single-hop by day usually still covers the peninsula. The Canaries need
  30/20 m (out of scope).
- **The antenna is the likeliest failure.** Low horizontal dipole at
  0.1–0.25 λ, pre-cut and colour-coded per band.

The default schedule is in [spec/band-plan.md](spec/band-plan.md) and
`keryx::bandplan`. It is an initial guess; Phase 0 logs replace it.

## 4. Physical layer: two lanes

### Why JS8's reliability is not magic

At equal energy per bit, modes perform similarly. JS8 Normal (~5 bit/s,
decodes to about −24 dB in 2.5 kHz) and Mercury DATAC15 (~68 bit/s, 90 %
delivery around −9.5 dB in 3 kHz) differ by ~14× in rate and ~14 dB in floor.
JS8 is reliable because it is slow. So: a slow lane that almost never fails,
plus faster lanes when the SNR allows.

JS8 also transmits constant envelope at full power for the whole frame. OFDM
needs back-off (Mercury notes ~10 dB PAPR on its 16QAM mode), which gives JS8
several more dB at the same peak power.

### Lane 1 — robust: JS8 waveform, binary frames

- JS8Call's text layer (Huffman varicode + `JSC` word dictionary) is designed
  for English text and wastes bits on ciphertext. We keep the 8-GFSK waveform
  and LDPC, and pack our own binary frames into it.
- Speeds: Slow (30 s frames, ~−28 dB), Normal (15 s, ~−24 dB), Fast (10 s,
  ~−20 dB), Turbo (6 s, ~−18 dB).
- Payload per frame: **assumed 9 bytes (72 bits), unverified**. Confirm in the
  JS8Call-improved engine before freezing the format (`JS8_FRAME_PAYLOAD_BYTES`).
- Works on the QDX (single-tone FSK transmitter) and every SSB radio.
- Engine: JS8Call-improved removed the Fortran in 2.4.0, and its Android port
  extracted a platform-independent C++ core with a JNI bridge. That is the
  starting point.

### Lane 2 — fast: Mercury (later)

- Mercury v2 (Rhizomatica, GPL-3.0): codec2/FreeDV OFDM data modes plus its
  own, adaptive ARQ ladder (DATAC15 → … → QAM16C2), HARQ chase combining,
  VARA-style TCP interface, AAudio backend.
- Needs a linear SSB transmitter (G90). Not the QDX, unless Mercury's
  experimental FSK modes mature.
- Not FreeDV voice. Voice is a separate, later topic.

### Not Reticulum

Announce ~157 bytes, link setup ~265 bytes, LXMF ~111 bytes per message. At
JS8 speeds that is minutes of airtime before content. It could ride the
Mercury lane later; it is not the core.

## 5. Architecture

```
        Field kit                                        Base station (24/7, Pi)
 ┌──────────────────────┐                         ┌──────────────────────────────┐
 │ Android app (Kotlin, │                         │ Bridges: Matrix (→ Signal,   │
 │ Compose M3)          │                         │ WhatsApp via mautrix),       │
 │  family / advanced   │                         │ MeshCore, ntfy               │
 ├──────────────────────┤                         ├──────────────────────────────┤
 │ Rust core (keryx)│                         │ pharos (Rust):           │
 │  codec, crypto, ARQ, │                         │  store and forward, ACKs,    │
 │  lane choice         │                         │  same keryx              │
 ├──────────────────────┤                         ├──────────────────────────────┤
 │ Modem: JS8 engine    │                         │ Multi-decoder: every JS8     │
 │  (+ Mercury later)   │                         │  speed + OFDM in parallel    │
 ├──────────────────────┤      HF NVIS            ├──────────────────────────────┤
 │ Radio: QDX or G90    │◄──── 40 / 60 / 80 m ───►│ Wideband SDR RX (all bands)  │
 │ via USB OTG          │                         │ + TX rig (G90)               │
 └──────────────────────┘                         └──────────────────────────────┘
```

- **No lane negotiation.** The base decodes every lane on every band at once
  (wideband SDR). Each ACK carries "heard you at X dB, clock offset Y". The
  sender picks its next lane from that report ([spec/arq-and-lanes.md](spec/arq-and-lanes.md)).
- **Store and forward.** Family replies wait at the base until the field
  station sends a `Poll` during a listen window.
- **Mirrored mode.** The base replies encrypted to a member iff that member's
  last message was encrypted, so emergency mode on the field side switches
  the base too.
- **A priori decoding (later).** The base knows which sender ids and slots to
  expect; FT8-style AP decoding can recover a couple of dB.

## 6. Protocol summary

Full specs: [frame-format](spec/frame-format.md), [crypto](spec/crypto.md),
[text codec](spec/text-codec.md), [ARQ and lanes](spec/arq-and-lanes.md).

Measured with the v0 code (8-byte tag, 9-byte JS8 frames):

| Message | Wire bytes | JS8 frames | Normal | Turbo |
|---|---|---|---|---|
| ACK | 9 | 1 | 15 s | 6 s |
| Stock phrase ("Estoy bien") | 13 | 2 | 30 s | 12 s |
| Estoy bien + position | 18 | 2 | 30 s | 12 s |
| 60-character Spanish text | 49 | 6 | 90 s | 36 s |

Add one ACK slot per exchange. With a 16-byte tag, add 8 bytes (usually one
more frame).

## 7. Security and regulation (summary)

- One pre-shared 256-bit group key per family, handed over by QR in person.
  Each member derives every sender's chain and then discards the group key, so
  a stolen phone cannot read traffic from before the theft.
- Per-message keys from per-sender hash ratchets; ChaCha20 encryption;
  HMAC-SHA256 tag truncated to 8 bytes (16 optional; pending decision).
- Two modes, one wire format: **signed plaintext** (default, legal on air) and
  **encrypted** (emergencies; dummy load for tests).
- Spain: Orden IET/1311/2013 Art. 30.2 forbids coding to hide meaning; 30.3
  limits third-party traffic to emergencies; Art. 29 requires the callsign at
  start, end and every 10 minutes. A 24/7 auto-replying base is an unattended
  automatic station: only recognised associations can be licensed for one,
  HF output ≤ 50 W. Details and open points: [regulatory-es.md](regulatory-es.md).

## 8. Hardware and Android

See [hardware.md](hardware.md). In short: QDX (5 W, FSK only) and G90
(20 W, SSB, tuner) today; QMX+ is the candidate for the family kit (internal
GPS and RTC solve clock sync; 160 m; SSB firmware might carry OFDM).
Android talks USB OTG natively: USB audio + usb-serial-for-android for CAT.
JS8 needs the clock within ~1–2 s: use GNSS time off-grid, plus the clock
offset in ACKs.

## 9. App UX

One app, two profiles.

- **Setup:** a QR code generated by the admin sets keys, member id, contacts,
  tag length and the band schedule.
- **Family mode:** four big buttons — *Estoy bien* (sends position),
  *Necesito ayuda* (confirm step), *Mensaje*, *Recibidos*. Delivery ticks:
  sent → heard by base → delivered → read. Bands and lanes are automatic.
- **Pre-flight check:** battery voltage, SWR if the radio reports it, GPS time.
- **Advanced mode** behind a PIN: emergency-encryption toggle, overrides,
  provisioning, log export.

## 10. Languages and repo layout

| Part | Language | Why |
|---|---|---|
| Protocol core (`core/crates/keryx`) | Rust | Memory-safe parsing of untrusted RF input; one implementation for app and base; good crypto crates; builds for Android (NDK) and aarch64 Pi |
| Base daemon (`core/crates/pharos`) | Rust | Same crate as the app, single static binary on the Pi |
| JS8 engine, Mercury, codec2 | C++ / C (upstream) | Reused as is behind one narrow FFI boundary |
| Android UI and platform glue | Kotlin + Jetpack Compose M3 | MD3's reference implementation; native USB, audio, services |
| Phase 0 prototype | Python (stdlib) | Throwaway glue to JS8Call's API; protocol work done by the `keryx` CLI |

Why not the alternatives:

- **C++ core:** same language as the JS8 engine, but hand-written parsing of
  hostile RF input is where C++ bites.
- **Go:** great for daemons, poor on Android (gomobile, cgo to C++); the
  protocol would end up implemented twice.
- **Python core:** cannot run inside the app; a second implementation.
- **Flutter / React Native:** still need native code for USB, audio and
  services, so you get the wrapper plus the native code. The JNI boundary to
  the C/C++ DSP is unavoidable either way; keep it narrow (UniFFI generates
  the Kotlin side).
- **Linux desktop later:** Compose Multiplatform reuses most of the Kotlin UI
  with MD3; the Rust core links directly there.

Layout:

```
core/           Rust workspace: keryx, keryx-cli (`keryx`), pharos; testvectors/
android/        Kotlin/Compose app (Gradle)
prototype/      Phase 0 Python glue (JS8Call API ↔ keryx CLI ↔ Matrix/ntfy)
docs/           this file, specs, decisions, research notes, roadmap
```

## 11. Ideas backlog

- Dead-man switch: no check-in by a set time → the bridge alerts the family
  with the last known position.
- Community receive-only nodes forwarding decodes to the base (no licence
  issue for receiving): diversity for the weak field-to-base direction.
- Voice notes over the fast lane: codec2 as data, the bridge converts to a
  Signal/WhatsApp voice note. Live encrypted voice is possible with codec2 +
  a stream cipher; FreeDV RADE cannot be bit-encrypted.
- MeshCore integration through its companion protocol (room server).
- Scheduled listen windows on the field side to save battery.
- Test without RF: codec2's `ch` simulator (Watterson fading) in CI.
- Soft combining of JS8 retransmissions (Mercury's HARQ idea).
- Rattlegram-style acoustic fallback if the USB cable fails.

## 12. Open questions

- [ ] Tag length: 8 bytes (default) or 16? (`TAG_LENS`, provisioning code `t8`/`t16`).
- [ ] JS8 frame payload size: 72 bits? Confirm in the engine.
- [ ] Segment marking: use JS8's frame-type bits for first/last (assumed), or spend a byte?
- [ ] Dial frequencies inside IARU R1 segments, away from JS8Call calling frequencies.
- [ ] Is compressed signed plaintext "coded to hide meaning"? See regulatory notes.
- [ ] SOS in clear (any listener can relay) or follow the current mode?
- [ ] Which Matrix bridges to run (mautrix-signal, mautrix-whatsapp; WhatsApp ban risk).
- [ ] Base station licensing route (association / radio club).

## 13. Corrections since the original chat

- A 60-character Spanish message measures **49 bytes / 6 JS8 frames**
  (≈90 s Normal, ≈36 s Turbo), not ~40 bytes / 5 frames. The static Huffman
  codec reaches ~4.9 bits per character; a word dictionary is the next gain.
- "Estoy bien + position" is **2 frames**, not 1–2.
- 60 m fills the dusk/dawn transitions, but **not** the winter pre-dawn gap
  (that needs 160 m).
