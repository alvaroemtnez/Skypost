# Research notes and sources

Figures used in the design, with where they come from. Checked October 2026.

## JS8 / JS8Call

- Speeds and decode floors: Slow 30 s / −28 dB, Normal 15 s / −24 dB, Fast
  10 s / −20 dB, Turbo 6 s / −18 dB; constant-envelope, full-duty 8-FSK.
  [JS8Call guide](https://www.scribd.com/document/753933321/JS8Call-Guide)
- Text layer: Huffman varicode + `JSC` word compression.
  [jsc.h](https://sources.debian.org/src/js8call/2.2.0%2Bds-2/jsc.h)
- JS8Call-improved: Fortran removed in 2.4.0 (Nov 2025); name back to JS8Call
  in 2.5.0. [User guide](https://js8call.com/JS8Call-improved/d6/d14/md_docs_2user__guide_2JS8Call__User__Guide.html)
- Current release 3.0.1; 60 m segment 5363 kHz added in 2.5.0.
  [Downloads](https://js8call.com/downloads.html)
- Android port: platform-agnostic core engine in `core/`, JNI bridge,
  AudioRecord capture, foreground decoding service. Beta.
  [JS8Call-improved/Android-port](https://github.com/JS8Call-improved/Android-port)
- Payload bits per frame: **not verified** (assumed 72).
- Measured floors with JS8Call-improved's own decoder (bad77ff), 2500 Hz,
  AWGN 50 %: Slow −22.6, Normal −19.8, Fast −16.8, JS8 40 −13.9 dB; fading
  90 %: about −16.5, −14.4, −10.6, −7.3 dB. The published figures above are
  3–5.5 dB optimistic. [highway-modems.md](highway-modems.md)

## Mercury (Rhizomatica)

- v2 in C, GPL-3.0; adaptive ARQ ladder DATAC15 → DATAC4 → DATAC3 → DATAC1 →
  DATAC17 → QAM16C2; control on DATAC16; HARQ chase combining; VARA-style
  TCP TNC; Reticulum via KISS; AAudio backend; experimental FSK_LDPC; 32-tone
  MFSK in progress. [Repo](https://github.com/Rhizomatica/mercury)
- DATAC15: 200 Hz, 30-byte payload, ~68 bit/s, AWGN 90 % point ≈ −9.5 dB;
  goodput DATAC1 ≈ 50 B/s, DATAC17 ≈ 98 B/s, QAM16C2 ≈ 142 B/s; QAM16C2
  PAPR ≈ 10 dB. [MODES.md](https://github.com/Rhizomatica/mercury/blob/mercuryv2/docs/MODES.md)

## XARA (VARA HF protocol reimplementation)

- MIT-licensed Rust reimplementation of VARA HF 4.9.0 / FM 4.4.0 by x0rro;
  releases 0.1.0–0.1.3 (21–31 Oct 2026), then discontinued upstream (repo
  still public); forks exist. 16 levels at 2300 Hz, 18–7050 bit/s; levels
  1–4 are single-tone FSK. [xara](https://github.com/x0rro/xara),
  [Zero Retries 0267](https://www.zeroretries.org/p/zero-retries-0267)
- VARA displays SNR normalised to its OFDM levels: FSK readings are about
  6.4 dB below true SNR. Full study: [highway-modems.md](highway-modems.md).

## Reticulum / LXMF

- LXMF overhead 111 bytes per message. [LXMF](https://www.github.com/markqvist/LXMF)
- Announce ~157 bytes, link setup ~265 bytes.
  [Reticulum docs history](https://git.hackliberty.org/Git-Mirrors/Reticulum/commit/fdf0456cf0595c0e02e199f02b008a6b636adba0)
- Minimum bitrate 5 bit/s. [API reference](https://reticulum.network/manual/reference.html)

## Spain

- Orden IET/1311/2013: Art. 29 (identification), 30.2 (no coding to hide
  meaning), 30.3 (third-party traffic only in emergencies), 18 and 25
  (unattended automatic stations), Annex I 4.3 (dummy loads).
  [BOE](https://www.boe.es/buscar/act.php?id=BOE-A-2013-7624)
- 60 m: 5351.5–5366.5 kHz, 15 W EIRP, any modulation.
  [Resolution](https://avance.digital.gob.es/espectro/radioaficionados/autorizaciones/Documents/Resolucion5MHz.pdf)

## Radios and Android

- QMX: 80/60/40/30/20 m, CW, FSK digital, SSB; SSB mode for multi-tone modes.
  [QMX](https://qrp-labs.com/qmx)
- QMX+: 160–6 m, optional internal GPS, battery-backed RTC.
  [QMX+](https://qrp-labs.com/qmxp)
- FT8CN with a QDX over one USB cable (audio + CAT).
  [Field report](https://qrper.com/2024/page/29)
- qFT8: Android, ft8_lib-based, JS8 free text, QMX/QDX USB; G90 via USB sound
  card interface. [qFT8](https://hamradio.my/?p=11872)
- WebUSB cannot claim CDC-ACM (QDX CAT) on Android. [FT8web](https://github.com/ok1cdj/FT8web)
- Ribbit / Rattlegram: encoded audio from a phone speaker into any radio.
  [ORI newsletter](https://www.openresearch.institute/?p=2631)

## Bridges (checked 2026-10-19, decision 0014)

- MeshCore: official Python library and CLI, serial/BLE/TCP companion
  protocol. [meshcore_py](https://github.com/meshcore-dev/meshcore_py),
  [meshcore-cli](https://github.com/meshcore-dev/meshcore-cli). Rust port,
  one maintainer, lists unsupported commands:
  [meshcore-rs](https://github.com/andrewdavidmackenzie/meshcore-rs).
- Signal: signal-cli, JSON-RPC daemon; needs JRE 25+; prebuilt libsignal
  only for x86_64; "releases older than three months may not work".
  [signal-cli](https://github.com/AsamK/signal-cli); aarch64 libsignal:
  [signal-libs-build](https://github.com/exquo/signal-libs-build). Rust
  alternative (AGPL, git dependency): [presage](https://github.com/whisperfish/presage).
  [signal-cli-api](https://crates.io/crates/signal-cli-api) is only a REST
  wrapper around signal-cli.
- WhatsApp: everything serious sits on [whatsmeow](https://github.com/tulir/whatsmeow)
  (Go, also behind mautrix-whatsapp). CLIs: [wacli](https://github.com/openclaw/wacli)
  (`sync --follow --events` NDJSON, linux arm64 releases),
  [whatsapp-cli](https://github.com/vicentereig/whatsapp-cli) (smaller, no arm64).
