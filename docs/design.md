# Skypost — design

Skypost is the project and app, Keryx the wire protocol, Pharos the base
station (`decisions/0011-naming.md`).

Status: phase 0/1 groundwork. Last updated 2026-10-19.
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
| A | Direct and group chats between field kits, fully off-grid. Short keyboard messages. | Licensed operator(s) |
| B | Field kit ↔ a 24/7 base station (Pharos) that bridges to Signal, WhatsApp and the MeshCore network. People there write to the Pharos and address a field user by alias. | Normal use: licensed operator. Emergency: anyone. |

Every station (phone or Pharos) has its own cryptographic identity, shared
by QR code, with a friendly alias and, for licensed operators, a callsign.

## 2. Decisions at a glance

| Topic | Decision | Record |
|---|---|---|
| Repo | One monorepo | [0001](decisions/0001-monorepo.md) |
| Core language | Rust, shared by app and base | [0002](decisions/0002-rust-core.md) |
| Android | Kotlin + Compose Material 3, no cross-platform UI framework | [0003](decisions/0003-kotlin-compose-m3.md) |
| Robust PHY | JS8 waveform carrying our own binary frames | [0004](decisions/0004-js8-waveform-binary-frames.md) |
| Fast PHY | Mercury (codec2 OFDM), later, G90-class radios only | [0005](decisions/0005-mercury-fast-lane.md) |
| Reticulum | Not used for the core | [0006](decisions/0006-no-reticulum-core.md) |
| Crypto | ChaCha20 + truncated HMAC-SHA256 (v0 group-key provisioning superseded) | [0007](decisions/0007-crypto.md) |
| Identities | X25519 identity per station, pairwise and group keys, 8-byte tags, no signatures on air (protocol v1) | [0015](decisions/0015-identities.md) |
| On-air default | Signed plaintext; encryption only in emergencies or into a dummy load | [0008](decisions/0008-signed-plaintext-default.md) |
| Lane choice | Base decodes every lane; sender picks from the SNR in ACKs | [0009](decisions/0009-no-lane-negotiation.md) |
| Python | Phase 0 prototype only, protocol via the Rust CLI (one exception: the MeshCore sidecar) | [0010](decisions/0010-python-phase0-only.md) |
| Bridges | Signal (signal-cli), WhatsApp (wacli), MeshCore (meshcore_py) as sidecars; no Matrix, no ntfy | [0014](decisions/0014-bridges-sidecars.md) |
| Pharos | Trusted Pharos list, discovery, registration, handles for bridged chats, alias-routing bot | [0016](decisions/0016-pharos-registration-and-bridging.md) |
| App | One Material 3 chat client (chat list, bubbles, new-chat picker, Pharos screen) | [0017](decisions/0017-chat-app-ui.md) |
| Licence | AGPL-3.0-or-later | [0018](decisions/0018-agpl.md) |
| Pharos access | Open, access code (proof on air, never the code) or closed; admin commands; one-to-one bridging only | [0019](decisions/0019-pharos-access.md) |

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
 │ Android app (Kotlin, │                         │ Bridge sidecars: Signal      │
 │ Compose M3)          │                         │ (signal-cli), WhatsApp       │
 │  family / advanced   │                         │ (wacli), MeshCore (py)       │
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
- **Store and forward.** Messages for a field user go out while it listens
  (each ping says for how long); otherwise they wait at Pharos until it hears
  the user again ([spec/pharos.md](spec/pharos.md)).
- **Mirrored mode.** Pharos replies encrypted to a user iff that user's last
  message was encrypted, so emergency mode on the field side switches Pharos
  too.
- **Bridge bot.** Signal/WhatsApp/MeshCore contacts write to the Pharos
  account and pick the field user by `@alias`; the bot asks when unclear.
- **A priori decoding (later).** The base knows which short ids and slots to
  expect; FT8-style AP decoding can recover a couple of dB.

## 6. Protocol summary

Full specs: [frame-format](spec/frame-format.md), [crypto](spec/crypto.md),
[text codec](spec/text-codec.md), [ARQ and lanes](spec/arq-and-lanes.md).

Full specs also cover [identities and URIs](spec/identity.md) and
[Pharos](spec/pharos.md). Measured with the v1 code (8-byte tag, 9-byte JS8
frames):

| Message | Wire bytes | JS8 frames | Normal | Turbo |
|---|---|---|---|---|
| ACK, ping | 9 | 1 | 15 s | 6 s |
| Stock phrase ("Estoy bien") | 13 | 2 | 30 s | 12 s |
| Estoy bien + position | 18 | 2 | 30 s | 12 s |
| 60-character Spanish text | 49 | 6 | 90 s | 36 s |

Add one ACK slot per exchange. Discovery, registration and bridge set-up
costs: [frame-format.md](spec/frame-format.md#airtime-8-byte-tag-9-byte-frames).

## 7. Security and regulation (summary)

- Each station has an X25519 identity, exchanged by QR in person. Two
  stations derive a pairwise key; groups share a random group key by QR.
- Stateless per-message keys (link, sender, counter); ChaCha20 encryption;
  HMAC-SHA256 tag truncated to 8 bytes. No signatures on air: 64 bytes would
  double a typical message.
- Two modes, one wire format: **signed plaintext** (default, legal on air,
  shield icon) and **encrypted** (emergencies; dummy load for tests; padlock).
  Both authenticate the sender (in groups: a member of the group).
- No forward secrecy since v1: a stolen identity key reads recorded encrypted
  traffic. Rotate identities after a loss.
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

One Material 3 chat app ([0017](decisions/0017-chat-app-ui.md)), laid out
like its sibling MeshConnect ([0023](decisions/0023-ui-like-meshconnect.md)).

- **First run:** create an identity (alias, optional callsign) or restore an
  encrypted backup.
- **Chat list:** direct, group, Signal, WhatsApp and MeshCore chats mixed,
  with filter chips; a red SOS button floating bottom right (held, then
  confirmed) with the new-chat button above it. Top bar: radio (icon and
  short name, e.g. QMX+), Pharos (signal bars for the SNR it last reported,
  and its name), search, overflow for my QR (a popup) and settings.
- **Chat:** bubbles; received messages show a shield (signed) or padlock
  (encrypted); sent ones show a clock, then a tick when the contact or Pharos
  ACKed. Composer with quick messages (stock phrases, *Estoy bien* + position)
  and an airtime estimate. Per-chat emergency encryption with confirmation,
  and a global override in Settings.
- **New chat:** direct (contact, scan or paste), group (create, scan or
  paste), Signal/WhatsApp (phone contact picker, typed number or recent),
  MeshCore (scan or paste its QR, or recent). Bridged chats need a trusted
  Pharos. Lists: swipe left to forget, hold to chat, block or delete.
- **Chat list gestures:** swipe right for read/unread, left to archive;
  hold for a menu (read, pin (3), archive, block, select, delete); delete
  asks first, then offers undo.
- **Blocking:** contacts, groups, bridged contacts and Pharos; blocked
  stations get no ACKs and no answers to pings (enforced in `keryx`).
- **Pharos card** (a sheet, from the top bar or Settings): the active Pharos
  with check, manage (opens the admin screen), QR and verify; trusted Pharos
  list, discovery, registration state. **Radio card:** state, and which radio
  (QDX, QMX, QMX+, G90 or a custom one with its CAT). New chat is a card too.
- **Settings:** profile, my QR, emergency contacts, emergency encryption for
  every chat, radio, ping interval, backup and restore, about.
- **SOS:** signed plaintext, so any listener can read and relay it; goes to
  the chats chosen as emergency contacts (any kind; bridged ones through the
  Pharos), or to every group by default.
- **Pre-flight check:** battery voltage, SWR if the radio reports it, GPS time.

## 10. Languages and repo layout

| Part | Language | Why |
|---|---|---|
| Protocol core (`core/crates/keryx`) | Rust | Memory-safe parsing of untrusted RF input; one implementation for app and base; good crypto crates; builds for Android (NDK) and aarch64 Pi |
| Base daemon (`core/crates/pharos`) | Rust | Same crate as the app, single static binary on the Pi |
| JS8 engine, Mercury, codec2 | C++ / C (upstream) | Reused as is behind one narrow FFI boundary |
| Android UI and platform glue | Kotlin + Jetpack Compose M3 | MD3's reference implementation; native USB, audio, services |
| Phase 0 prototype | Python (stdlib) | Throwaway glue to JS8Call's API; protocol work done by the `keryx` CLI |
| Bridges | Upstream programs (Java, Go, Python) as sidecars | Maintained clients instead of our own; Pharos speaks JSON to them ([0014](decisions/0014-bridges-sidecars.md)) |

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
prototype/      Phase 0 Python glue (JS8Call API ↔ keryx CLI ↔ signal-cli)
sidecars/       bridge sidecars we own (MeshCore, later)
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
- Scheduled listen windows on the field side to save battery.
- Test without RF: codec2's `ch` simulator (Watterson fading) in CI.
- Soft combining of JS8 retransmissions (Mercury's HARQ idea).
- Rattlegram-style acoustic fallback if the USB cable fails.

## 12. Open questions

- [x] Tag length: 8 bytes ([0015](decisions/0015-identities.md)).
- [ ] JS8 frame payload size: 72 bits? Confirm in the engine.
- [ ] Segment marking: use JS8's frame-type bits for first/last (assumed), or spend a byte?
- [ ] Dial frequencies inside IARU R1 segments, away from JS8Call calling frequencies.
- [ ] Is compressed signed plaintext "coded to hide meaning"? See regulatory notes.
- [x] SOS in clear (signed plaintext), so any listener can relay it.
- [ ] Forward secrecy for encrypted mode: on-air ephemeral rekey (32 B each way)?
- [ ] Is relaying bridged chats third-party traffic beyond Art. 30.3? Ask URE.
- [x] Which bridges to run: signal-cli, wacli, meshcore_py sidecars ([0014](decisions/0014-bridges-sidecars.md)).
- [ ] Does `wacli --events` carry incoming message content? Else a Go sidecar on `whatsmeow`.
- [ ] Base station licensing route (association / radio club).

## 13. Corrections since the original chat

- A 60-character Spanish message measures **49 bytes / 6 JS8 frames**
  (≈90 s Normal, ≈36 s Turbo), not ~40 bytes / 5 frames. The static Huffman
  codec reaches ~4.9 bits per character; a word dictionary is the next gain.
- "Estoy bien + position" is **2 frames**, not 1–2.
- 60 m fills the dusk/dawn transitions, but **not** the winter pre-dawn gap
  (that needs 160 m).
