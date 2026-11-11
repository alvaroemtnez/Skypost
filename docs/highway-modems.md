# Highway modems: XARA (VARA HF protocol) vs Mercury, against JS8

Status: study, 11 November 2026. Input for the roadmap item "JS8 as the
control channel, … highways" and for revisiting
[decision 0005](decisions/0005-mercury-fast-lane.md). No decision is taken
here.

Read this before choosing the highway modem, its speed levels and when to
open a highway. Everything measured can be rerun with
[highway-sim/](highway-sim/README.md); raw results are in
`highway-sim/results/`.

## 1. Summary

- **JS8 stays.** Below about −11 dB (90 % frame success on fading channels,
  2500 Hz) only JS8 gets through, and it needs no session setup. Nothing here
  replaces it as control channel and weak-signal lane.
- **XARA's FSK levels 1–4 are a real QDX highway.** They transmit one tone at
  a time, so a QDX can in principle key them (bench test pending). End to end,
  with XARA's own ARQ on fading channels, they deliver about 1.5× JS8's best
  speed at −8 dB, 2–3× at −6 dB and 3–5× from −4 dB up. That is 32–62 bit/s
  at −4 dB, where JS8 tops out at about 12. Against Mercury's QDX mode
  (`mfsk_tx_only`, its MFSK floors): Mercury is level or slightly ahead from
  −10 to −8 dB, and XARA is 1.4–2.9× faster from −6 dB up.
- **On linear radios, it depends on transfer size.** Measured end to end
  against Mercury's own bench on the same CCIR channels:
  - **Long transfers (32 KB):** XARA is level to 1.7× faster at 5–10 dB, and
    1.2–2.7× faster from 15 dB up.
  - **Short transfers (4 KB):** Mercury is ahead at 5–10 dB, by up to 1.75×,
    because XARA climbs through its levels slowly.
  - **Below 5 dB:** XARA is ahead.
- **Disturbed NVIS (ITU-R F.1487, 7 ms echoes) breaks both modems' fast
  modes.** XARA's levels 11–16 and Mercury's DATAC3/DATAC1/DATAC17 never reach
  50 % there. XARA still has PSK levels 5–10 that work, which gives it
  2.5–5× Mercury's simulated best rate at 4–24 dB on that channel. (Mercury
  publishes no end-to-end NVIS bench.)
- **The JS8 floors in `keryx::lane` are optimistic.** Measured with
  JS8Call-improved's own decoder, the AWGN 50 % points are 3–5.5 dB worse
  than the published floors (−28, −24, −20, −18 dB). On fading, 90 % success
  needs a further 4–6 dB. With today's 6 dB margin, Normal, Fast and Turbo
  would each be chosen 3–5 dB too early.
- **Recommendation:** make XARA (forked for Skypost-only links) the primary
  highway candidate, keep Mercury as the fallback behind the same interface,
  and run the four checks in section 9 before deciding.

## 2. Candidates

### JS8 (baseline, already the control lane)

- JS8Call-improved, C++ decoder (`JS8_Mode/JS8.cpp`), 8-FSK, 79 symbols,
  LDPC(174,87), slotted. Skypost assumes 9 bytes per frame (unverified).
- Speeds: Slow 30 s, Normal 15 s, Fast 10 s, JS8 40 (was Turbo) 6 s,
  JS8 60 4 s. JS8 60 is "not for messages" upstream and fails with any carrier
  offset in our tests; it is left out.

### XARA = the VARA HF 4.9 protocol, reimplemented

- What it is: x0rro's MIT-licensed Rust reimplementation of VARA HF 4.9.0 and
  VARA FM 4.4.0, interoperable with real VARA. Releases 0.1.0 (21 Oct 2026)
  to 0.1.3 (31 Oct). The author built it from observed behaviour, a written
  spec and test vectors, not decompilation. Upstream development and
  distribution were **discontinued** in early November: the repo is still
  public, and its README points users to Mercury and openmodem. Forks exist:
  NR5P is porting it to macOS, iOS and Android (not tested with a radio, no
  USB PTT yet); sp2ong, haklein and randal007 also have forks.
- Code: ~40k lines in five crates. `xara-phy` (~11k) holds the waveforms,
  turbo decoder and receivers; `xara-arq` (~9.6k) is a sans-IO ARQ (no I/O of
  its own, like `keryx`); there are also host ports, a web UI and the app. It
  embeds into Pharos and the Android NDK like `keryx`.
- The "registered" cap: unregistered sessions stop at level 4. That is one
  bit in the caller's connect header, with no key check
  (`xara-arq/src/hf/frames.rs`, `UNREGISTERED_MAX_LEVEL`). Between Skypost
  stations running XARA it is only a constant in MIT code; a fork removes it.
  VARA's licence covers VARA's software, which Skypost never runs or talks to.
- Legal (EU): the Software Directive 2009/24/EC allows observing, studying
  and testing a program (Art. 5(3)) and decompiling it for interoperability
  (Art. 6), and Art. 8 voids contract terms against either. CJEU *SAS v WPL*
  (C-406/10) holds that functionality and data formats are not protected by
  copyright. The owner considers the legal risk negligible.
- HF bandwidths 500, 2300 and 2750 Hz. At 2300 Hz there are 16 levels (table
  in `xara-phy/src/hf/level.rs`; the rates match the published VARA table):

| Level | Modulation | Frame (s) | Payload (B) | Gross bit/s | PAPR (dB) |
|---|---|---|---|---|---|
| 1 | 8-FSK, 23.4 Bd, tone hopping | 5.29 | 9 | 18 | 3 |
| 2 | 8-FSK, 46.9 Bd | 4.86 | 22 | 41 | 3 |
| 3 | 16-FSK, 46.9 Bd | 4.86 | 47 | 82 | 3 |
| 4 | 16-FSK, 93.75 Bd | 4.21 | 89 | 175 | 3 |
| 5–9 | QPSK, 3–10 carriers | 4.00–3.71 | 132–425 | 270–922 | 6.1–8.9 |
| 10–14 | OFDM 49 carriers, QPSK/8PSK | 3.58 | 896–2156 | 2011–4830 | 9.4 |
| 15–16 | OFDM 16/32QAM | 3.70 | 2710–3254 | 5872–7050 | 9.3 |

- ARQ facts that matter for highways:
  - From the 6th stay at a level, it sends 2 frames per turn.
  - ACKs come in three formats: F0 (32 symbols, single tone), F1 (16 symbols,
    single tone) and F2 (8 symbols, **two tones at once**). The receiver
    picks F2 only above about −3.5 dB raw SNR.
  - Each direction adapts on its own (`next_tx_level`, `rx_level`).
  - The transmitter backs off by PAPR + 2 + (10 − PAPR)/5 dB
    (`xara-phy/src/hf/tx.rs`), so it behaves like a peak-power-limited
    radio.
- **SNR display trap:** XARA, like VARA, displays SNR normalised to the OFDM
  levels' PAPR. FSK readings show about 6.4 dB below the true SNR (2500 Hz),
  so VarAC's "level 1 decodes at −21 dB" is about −14.6 dB true. The level
  table's thresholds (L1 −13.6 … L4 −6.6) are true SNR.

### Mercury (Rhizomatica)

- GPL-3, C, actively developed and funded by ARDC. Release 1.9.23; `main` is
  the 2.0 "carousel" ARQ, which does **not** connect to 1.9.x. 1.9.20 broke
  compatibility with 1.9.19 too. Pin one version on every station.
- The ladder: 1 kHz MFSK floor (32 tones, ~60 bit/s raw) and 500 Hz MFSK
  (31 bit/s), then DATAC15 (68), DATAC4 (87), DATAC3 (321), DATAC1 (980),
  DATAC17 (1410) and QAM16C2 (3100 bit/s raw, PAPR about 10 dB). All are at
  most 2.1 kHz wide. DATAC16 carries control.
- The ARQ is receiver-driven, with Reed-Solomon erasure coding: rounds of up
  to 30 s keyed, no per-frame ACKs, and a lost frame is replaced by any other
  piece of its block.
- `mfsk_tx_only` exists for FSK-only radios and names the QDX: everything
  that station sends stays on the MFSK floor, and receive is unaffected. It
  has never been keyed on a real FSK-only radio.
- It also has a VARA-style TCP TNC, a broadcast port, a RaptorQ file
  carousel, busy detection, and an Android preview (USB OTG, must stay in the
  foreground).

## 3. Method

Full details and pitfalls are in [highway-sim/README.md](highway-sim/README.md).

- **Frame level:** each modem's own transmitter and receiver
  (`xara-phy`; JS8Call-improved `JS8.cpp`; Mercury's MFSK backend and the
  vendored codec2 DATAC tools).
  - Channel: Mercury's validated Watterson simulator at 8 kHz, with presets
    AWGN, CCIR Good (0.5 ms, 0.1 Hz), Moderate (1 ms, 0.5 Hz), Poor (2 ms,
    1 Hz) and ITU-R F.1487 disturbed NVIS (7 ms, 1 Hz).
  - Impairments: a random ±10 Hz carrier offset per frame, plus 0–200 ms
    start jitter for JS8.
  - Scale: 100–200 frames per point, 1 dB steps.
  - SNR: average power over noise in 2500 Hz (JS8's convention; Mercury's
    SNR3k reads 0.8 dB lower). Every modem gets equal average power.
- **Goodput envelopes:** frame success multiplied by each protocol's airtime
  (JS8 slots; XARA's two frames plus ACK; Mercury's rounds plus poll),
  taking the best mode at each SNR. This is an upper bound with ideal
  adaptation.
- **End-to-end ARQ for XARA:** two complete XARA stations
  (`xara::station::hf::Station`) linked through a streaming Watterson channel
  in each direction (`xsim/src/bin/xarq.rs`). That channel was checked
  against Mercury's tool and agrees within a few percent per point. The test
  connects, then transfers 4 KB or 32 KB of incompressible data, with 3 or 2
  seeds per point. The SNR is the measured in-burst SNR of the payload
  direction, the same method as Mercury's own bench, so its published
  numbers (Mercury commit `097271d`, `docs/channel-figures/`) are the Mercury
  side of the comparison.
- **Calibration checks:** our pipeline reproduces Mercury's published floors
  (MFSK 50 % at −11.2 dB SNR3k; DATAC15 90 % near −9.5 dB SNR3k) and FT8's
  −20.8 dB for JS8 Normal (we measured −19.8).

Not modelled:
- PEP limits. Constant-envelope modes (JS8, XARA L1–4, MFSK) get roughly
  5–9 dB more average power than PSK/OFDM from the same radio.
- Session setup at weak SNR beyond XARA's own connect.
- Turnaround latency of real radios.
- QRM, impulse noise, AGC and receive filters.
- Mercury's own modem wrapper for the DATAC rungs: we used codec2's reference
  receiver, and QAM16C2 is not in it.
- One fading seed per channel at frame level.

## 4. Results: frame success thresholds

SNR in dB (2500 Hz) at 50 % / 90 % frame success; "n/a" means 90 % (or 50 %)
was never reached up to the top of the sweep.

| Modem | Mode | AWGN | CCIR Good | CCIR Moderate | CCIR Poor | NVIS disturbed |
|---|---|---|---|---|---|---|
| JS8 | Slow | −22.6 / −21.0 | −20.3 / −16.0 | −19.6 / −17.1 | −19.2 / −17.0 | −19.0 / −16.3 |
| JS8 | Normal | −19.8 / −18.3 | −18.0 / −12.7 | −16.9 / −14.2 | −16.8 / −14.6 | −16.9 / −14.2 |
| JS8 | Fast | −16.8 / −15.0 | −14.6 / −8.8 | −14.3 / −10.5 | −14.1 / −10.7 | −13.8 / −10.5 |
| JS8 | JS8 40 | −13.9 / −12.0 | −11.8 / −4.8 | −11.3 / −7.4 | −10.9 / −7.4 | −10.8 / −7.2 |
| XARA | L1 | −15.3 / −13.8 | −13.5 / −8.7 | −13.6 / −11.0 | −13.6 / −11.2 | −13.4 / −11.2 |
| XARA | L2 | −12.5 / −11.5 | −10.6 / −6.9 | −10.9 / −8.7 | −10.7 / −9.1 | −9.8 / −8.1 |
| XARA | L3 | −10.5 / −10.0 | −8.4 / −3.6 | −8.2 / −6.0 | −7.8 / −6.2 | −6.8 / −5.1 |
| XARA | L4 | −7.5 / −7.0 | −5.2 / −0.2 | −5.1 / −2.7 | −4.4 / −2.6 | −0.2 / n/a |
| XARA | L5 | −5.4 / −4.5 | −3.4 / 1.7 | −3.2 / −0.6 | −2.6 / −1.0 | 0.8 / 3.5 |
| XARA | L6 | −4.1 / −3.2 | −1.7 / 3.4 | −1.7 / 0.6 | −1.0 / 1.8 | 2.3 / 4.4 |
| XARA | L7 | −2.4 / −1.7 | 0.0 / 5.0 | 0.1 / 1.8 | 0.0 / 2.2 | 6.1 / n/a |
| XARA | L8 | −1.2 / −0.3 | 0.7 / 6.3 | 1.2 / 3.5 | 1.5 / 3.6 | 7.7 / 13.0 |
| XARA | L9 | −0.1 / 0.8 | 1.9 / 7.5 | 2.4 / 4.6 | 2.4 / 4.4 | 9.4 / n/a |
| XARA | L10 | 3.6 / 4.5 | 6.0 / 11.9 | 5.9 / 8.6 | 6.2 / 8.1 | 13.5 / n/a |
| XARA | L11 | 5.5 / 6.0 | 8.7 / 14.6 | 8.8 / 11.0 | 9.3 / 11.3 | n/a / n/a |
| XARA | L12 | 7.4 / 7.9 | 11.8 / 18.2 | 11.8 / 14.2 | 12.5 / 14.7 | n/a / n/a |
| XARA | L13 | 9.5 / 9.9 | 12.8 / 18.7 | 12.5 / 15.5 | 13.3 / 16.5 | n/a / n/a |
| XARA | L14 | 11.5 / 12.0 | 15.6 / 21.8 | 15.6 / 19.0 | 17.3 / 25.5 | n/a / n/a |
| XARA | L15 | 12.5 / 12.9 | 17.2 / 22.5 | 17.4 / 19.8 | 18.3 / 26.2 | n/a / n/a |
| XARA | L16 | 15.5 / 15.9 | 19.4 / 24.7 | 19.6 / 22.7 | 21.2 / 33.2 | n/a / n/a |
| Mercury | MFSK 500 Hz | −14.1 / −13.1 | −11.3 / −8.1 | −11.2 / −8.8 | −11.3 / −8.9 | −11.2 / −9.3 |
| Mercury | MFSK 1 kHz | −11.3 / −10.2 | −9.3 / −5.4 | −8.8 / −5.4 | −9.0 / −5.8 | −8.6 / −5.7 |
| Mercury | DATAC15 | −11.2 / −10.0 | −9.2 / −1.5 | −7.4 / 0.5 | −7.6 / −3.4 | −5.8 / n/a |
| Mercury | DATAC4 | −9.3 / −8.1 | −7.8 / −0.5 | −6.5 / −2.5 | −6.6 / −3.0 | −3.0 / n/a |
| Mercury | DATAC3 | −4.5 / −4.1 | −2.7 / 4.5 | −1.3 / 2.3 | −1.9 / 1.3 | n/a / n/a |
| Mercury | DATAC1 | 0.5 / 0.9 | 2.7 / 6.8 | 3.3 / 5.9 | 3.4 / 5.0 | n/a / n/a |
| Mercury | DATAC17 | 3.5 / 3.9 | 6.1 / 10.8 | 6.7 / 9.2 | 6.5 / 8.0 | n/a / n/a |

CCIR Good's slow 0.1 Hz fades leave long holes, so its 90 % points sit far
from its 50 % points for every modem.

## 5. Results: what reaches the other end

### 5.1 Best-mode goodput envelope (frame level × airtime, ideal adaptation), bit/s

CCIR Poor (left) / ITU disturbed NVIS (right). "QDX tx" means only the modes
a single-tone transmitter can send.

| SNR | JS8 | XARA L1–4 (QDX tx) | Mercury MFSK (QDX tx) | XARA all | Mercury all |
|---|---|---|---|---|---|
| −14 | 5 / 4 | 5 / 4 | – / – | 5 / 4 | – / – |
| −12 | 6 / 6 | 10 / 9 | 4 / 3 | 10 / 9 | 4 / 3 |
| −10 | 8 / 8 | 21 / 14 | 21 / 19 | 21 / 14 | 21 / 19 |
| −8 | 10 / 10 | 30 / 28 | 33 / 34 | 30 / 28 | 33 / 34 |
| −6 | 12 / 12 | 59 / 46 | 42 / 42 | 59 / 46 | 42 / 42 |
| −4 | 12 / 12 | 84 / 63 | 45 / 44 | 84 / 63 | 48 / 44 |
| −2 | 12 / 12 | 132 / 64 | 46 / 46 | 153 / 64 | 103 / 46 |
| 0 | 12 / 12 | 136 / 70 | 47 / 46 | 228 / 70 | 176 / 46 |
| 4 | 12 / 12 | 136 / 104 | 47 / 46 | 663 / 254 | 486 / 77 |
| 8 | 12 / 12 | 136 / 108 | 47 / 46 | 1556 / 346 | 1001 / 138 |
| 12 | 12 / 12 | 136 / 108 | 47 / 46 | 2264 / 577 | 1079 / 203 |
| 16 | 12 / 12 | 136 / 108 | 47 / 46 | 2944 / 1119 | 1102 / 275 |
| 24 | 12 / 12 | 136 / 108 | 47 / 46 | 4984 / 1329 | 1102 / 275 |

Mercury's top rung, QAM16C2, is missing from the simulation; Mercury's own
bench reaches about 2.1 kbit/s on AWGN with it. All channels are in
`highway-sim/results/analysis.md`.

### 5.2 End-to-end ARQ, bit/s after link-up (in-burst SNR, 2500 Hz)

XARA was measured here (two full stations); Mercury figures are from its own
published bench (same CCIR definitions, SNR3k converted). "–" means no data
at that SNR; Mercury has no NVIS bench.

| Channel, size | Modem | −10 | −5 | 0 | 5 | 10 | 15 | 20 |
|---|---|---|---|---|---|---|---|---|
| CCIR Poor, 4 KB | XARA | 11 | 42 | 130 | 280 | 560 | 941 | 1010 |
| | Mercury | – | – | 52 | 305 | 805 | 883 | – |
| CCIR Poor, 32 KB | XARA | 11 | 43 | 139 | 412 | 933 | 1793 | 2544 |
| | Mercury | – | – | – | 346 | 888 | 879 | 944 |
| CCIR Moderate, 32 KB | XARA | 10 | 40 | 133 | 448 | 1001 | 1748 | 2758 |
| | Mercury | – | – | – | – | 1038 | 1182 | 1579 |
| CCIR Good, 32 KB | XARA | 9 | 28 | 110 | 373 | 849 | 1538 | 2663 |
| | Mercury | – | – | – | 223 | 636 | 1061 | 1849 |
| AWGN, 4 KB | XARA | 20 | 96 | 179 | 465 | 706 | 1086 | 1187 |
| | Mercury | – | – | 130 | 784 | 1027 | – | – |
| AWGN, 32 KB | XARA | 20 | 100 | 187 | 724 | 1779 | 2633 | 3588 |
| | Mercury | – | – | – | – | 1423 | 2160 | 2092 |
| NVIS disturbed, 4 KB | XARA | 11 | 33 | 50 | 84 | 163 | 308 | 331 |
| NVIS disturbed, 32 KB | XARA | 10 | 33 | 52 | 77 | 100 | 190 | 427 |

- Including connect (about 8 s), XARA's 4 KB figures drop by 5–20 %
  (`results/arq-analysis.md`). Short transfers are limited by the climb
  through the levels: 4 KB on a clean channel took 27.6 s after link-up
  whether the SNR was 10 or 30 dB.
- **Low end, fading channels (CCIR Good/Moderate/Poor and NVIS), 4 KB,
  XARA:**

| In-burst SNR | Delivered | Rate |
|---|---|---|
| −12 dB | 1.1–1.8 KB in 30 min | 5–8 bit/s |
| −10 dB | 1.8–2.4 KB in 30 min | 8–11 bit/s |
| −8 dB | 2.7–4 KB in 30 min | 12–18 bit/s |
| −6 dB | all 4 KB | 19–36 bit/s |
| −4 dB | all 4 KB | 32–62 bit/s |
| −2 dB | all 4 KB | 43–113 bit/s |
| 0 dB | all 4 KB | 45–134 bit/s |

  Links came up in every run, down to −12 dB in-burst.
- **Mercury below 1 dB** (its own bench): 18 of 168 4 KB runs did not finish,
  with the link running on the MFSK floor at about 4 B/s (32 bit/s).
- **Cross-check with real VARA:** N5TW's 2020 end-to-end Winlink tests on CCIR
  Poor measured 200 / 490 / 950 bit/s at about 6 / 11 / 16 dB (2500 Hz). Our
  XARA reaches roughly 1.5–2× that. The difference is plausibly Winlink and
  B2F overhead plus the 2020 VARA version. Real XARA links should be expected
  somewhat below these simulated figures.

## 6. Findings

1. **JS8 vs XARA levels 1–4.** Per bit they are about equally efficient, so
   JS8 reaches about 5 dB deeper and XARA runs faster once the SNR allows it.
   - L1 is roughly JS8 Fast in reach (90 % at about −11 dB on fading) and
     1.5× its rate. It brings little.
   - L2 is 1 dB more robust than JS8 40 and 2.5× faster.
   - L3 and L4 have no JS8 counterpart: 64 and 136 bit/s at about −6 and
     −3 dB.
   - End to end, the ARQ costs XARA up to half of that envelope at the low
     end. That leaves about 1.5× JS8 at −8 dB, 2–3× at −6 dB and 3–5× from
     −4 dB up.
2. **XARA vs Mercury for a QDX transmitter.** XARA L1–4 against Mercury's two
   MFSK floors:
   - At −10 to −8 dB they are level, or Mercury is slightly ahead (on NVIS,
     19–34 against 14–28 bit/s).
   - From −6 dB up, XARA is 1.4–2.9× faster on CCIR and 1.1–2.3× on
     disturbed NVIS.
   - L4 does not reach 90 % on disturbed NVIS; its 10.7 ms symbols have no
     guard against 7 ms echoes. On that channel the QDX direction tops out at
     about 100 bit/s.
3. **XARA vs Mercury on linear radios.**
   - **Below 5 dB, XARA is ahead,** because its FSK and PSK levels fill the
     gap between Mercury's MFSK and DATAC rungs.
   - **At 5–10 dB it depends on transfer size.** XARA is level to 1.7× ahead
     on 32 KB transfers, but Mercury is ahead on 4 KB transfers (by up to
     1.75×), because XARA's climb through the levels is slow.
   - **From 15 dB up, XARA is 1.2–2.7× ahead,** because Mercury's 2.1 kHz
     ladder tops out early.
4. **Disturbed NVIS.** Echoes of 7 ms, longer than either modem's OFDM guard
   interval (XARA 2.7 ms), kill the OFDM modes regardless of SNR. That is the
   "fade cliff" Mercury's own team documented. XARA's PSK levels 5–9 (3–10
   carriers) survive and Mercury's DATAC3/1/17 do not. The SNR reads healthy
   while the fast levels fail, so any adaptation must learn from delivery,
   not SNR alone.
5. **JS8 decode floors.** These are the published figures `keryx::lane` uses,
   against our measurements:

| Speed | Published floor | AWGN, 50 % | Fading, 90 % |
|---|---|---|---|
| Slow | −28 | −22.6 | −16 to −17 |
| Normal | −24 | −19.8 | −14.2 to −14.6 |
| Fast | −20 | −16.8 | −10.5 to −10.7 |
| Turbo (JS8 40) | −18 | −13.9 | −7.2 to −7.4 |

   Slow gains only about 2.5 dB over Normal on fading, not the nominal 3 dB.
   These are true SNRs. What each JS8 decoder *reports* still needs
   calibrating against true SNR in Phase 0.
6. **Mercury's `OfdmFast` floor in `arq-and-lanes.md`** (−9.5 dB, DATAC15 on
   AWGN) is optimistic for HF: DATAC15's 90 % point is −3.4 to +0.5 dB on
   CCIR, and it never reaches 90 % on disturbed NVIS.
7. **Upstream quirks found.** These are worth reporting upstream:
   - JS8Call-improved's JS8 60 decoder stops working under
     `LC_CTYPE=C.UTF-8`, and with any carrier offset.
   - Its decode results move by about ±1 dB with frame timing on a perfect
     grid.
   - Mercury's raw DATAC tools break on `--quiet`.

## 7. The QDX question

- **Transmit only.** The QDX synthesizes one carrier from the measured audio
  frequency, so it can only send single-tone FSK. Its receiver is a normal
  linear one: it can receive every XARA level and every Mercury rung.
- **Both modems adapt each direction on its own,** so a Pharos or a G90 kit
  can send fast PSK/OFDM to a QDX kit while the QDX answers on L1–4. The path
  towards the kit is also usually the stronger one, because the Pharos has
  more power.
- **XARA changes needed** (both are changes to the on-air protocol, which is
  fine for Skypost-only links):
  - Never ask a QDX station for the two-tone F2 reply format.
  - Never climb a QDX's transmit direction above L4.
  - The radio's "maximum transmit level" (4 for a QDX, 16 for a G90) travels
    in the highway handshake. That settles the roadmap's open item "OFDM
    capability belongs to the radio, not the identity".
- **Mercury** already has `mfsk_tx_only`. Its QDX direction then stays on the
  MFSK floor, about 30–60 bit/s.
- **Unverified on hardware for both:**
  - Does the QDX key L1–4 cleanly? These are hard-keyed FSK at 23, 47 and
    94 baud, with tone hopping.
  - Does it switch TX/RX within XARA's ACK windows (about 0.2–0.7 s)?
  - Does its receive filter pass 375–2670 Hz for XARA's 2300 Hz OFDM? If not,
    use the 500 Hz mode or Mercury's 2.1 kHz modes.
- **The QDX advantage is time-limited.** `hardware.md` plans QMX+ kits, which
  may carry linear SSB.

## 8. Voice notes and other payloads over a highway

Codec2 sizes: 450 bit/s ≈ 3.4 KB/min, 700C ≈ 5.3 KB/min, 1300 ≈ 9.8 KB/min.

| Path | Rate | 1 min of 700C |
|---|---|---|
| QDX → anyone at −4 to 0 dB, XARA L3–L4 end to end | 32–134 bit/s | 5–22 min |
| Linear → QDX kit at 5–10 dB, XARA | 300–900 bit/s | 50–140 s |

Short notes (10–20 s) from a QDX are workable. Default to 450 or 700C, with a
length cap on QDX kits. XARA's ARQ carries arbitrary bytes, with compression
off for binary data.

## 9. Recommendation

**Primary highway candidate: XARA, forked for Skypost-only links, 2300 Hz
mode. Fallback: Mercury behind the same interface. Do not ship both.**

Why:
- It is faster, or level, in almost every regime measured: QDX transmit from
  −6 dB, low SNR, long transfers at mid SNR, high SNR, and disturbed NVIS.
  The exceptions are short transfers at 5–10 dB and the MFSK floor at −10 to
  −8 dB, where Mercury is ahead by up to 1.75×.
- It is Rust with a sans-IO ARQ, so it embeds like `keryx` in Pharos and the
  app with no daemon or TCP.
- The wire protocol is frozen. Skypost can still change it (QDX caps, F2) and
  owns the result.

Against it:
- **We would maintain about 21k lines of signal-processing and ARQ code we
  didn't write.** Upstream is discontinued and the forks are young.
- **The simulation shows VARA's ARQ is far from ideal at the low end** (up to
  half the envelope), and real VARA has measured below our simulated XARA.
- **No over-the-air evidence yet**, on NVIS or with a QDX.
- **Mercury has a funded team, better documentation** (including TLA+ specs)
  and NVIS deployment experience, and is improving quickly. A future Mercury
  with faster FSK rungs, or OFDM with a longer guard interval, could close the
  gap.

Proposed highway parameters, to confirm in Phase 0 and Phase 3:
- **When to open a highway:** reported SNR (after calibration) at least about
  −5 dB true in 2500 Hz, and enough queued data to beat JS8 including the
  connect (about 8 s plus the handshake). As a starting point, more than about
  200 B queued. Between −10 and −6 dB the end-to-end gain over JS8 is only
  1–3×, so stay on JS8 unless the queue is large.
- **Start level:** XARA's climb through the levels dominates transfers of a
  few KB. A highway opened from a known JS8 SNR could start higher than
  XARA's default (a fork change), which would narrow Mercury's short-transfer
  lead.
- **Levels:** 2300 Hz. Cap each direction's transmit at the radio's
  capability (QDX: L4). On links that look like disturbed NVIS (delivery
  failing at healthy SNR), cap at L10 and let delivery, not SNR, drive the
  climb.
- **Control traffic stays on JS8** (Slow/Normal). The highway handshake runs
  on JS8 and names the dial frequency, bandwidth and each side's maximum
  transmit level.

Before deciding, run these four checks:
1. **Bench a QDX keying XARA L1–4** into a dummy load: spectrum, keying
   cleanliness, TX/RX switching against XARA's timing.
2. **Run XARA ↔ XARA over the air on NVIS** (G90 and QDX) with the
   `--record` flag, and compare with these numbers.
3. **Rerun this simulation with PEP-limited transmit levels,** and with
   Mercury's own 2.0 carousel end to end on NVIS. Mercury publishes no NVIS
   bench.
4. **Decide who maintains the fork,** and track the XARA forks for a month.

**Follow-ups regardless of the modem:**
- Re-derive the JS8 lane floors and margin in `keryx::lane` and
  `spec/arq-and-lanes.md` from Phase 0 logs, using section 6.5 as the prior.
- Calibrate the reported SNR against these true-SNR figures.

## 10. Watch list (new information that would change this)

- **XARA:** does any fork become the maintained upstream (NR5P, sp2ong,
  haklein, randal007, others)? Does an Android build gain USB PTT? Does
  anyone publish XARA ↔ VARA or XARA ↔ XARA on-air measurements, or other
  independent VARA reimplementations?
- **VARA's author (EA5HVK):** any statement or legal action, or VARA
  changing its protocol (XARA tracks 4.9.0 only; Skypost-only links would
  not care).
- **Mercury:**
  - When 2.0 (the carousel) is released, and its compatibility promises.
  - Any faster FSK rungs for `mfsk_tx_only`.
  - OFDM modes with longer guard intervals for NVIS.
  - Any NVIS bench of its own.
  - On-air reports of `mfsk_tx_only` with a QDX.
- **JS8Call-improved:** fixes to the issues in section 6.7. Its JS8 decoder
  is moving fast, so re-measure the floors before tuning `keryx::lane`.
- **Spanish regulations:** any requirement for openly published protocols
  (the XARA source is public either way).

## 11. Sources

- XARA: github.com/x0rro/xara (discontinued notice); local clone 0.1.3;
  forks sp2ong, haklein, randal007, NR5P (nr5p.com/xara-fork.html); AUR
  `xara` 0.1.3.
- Zero Retries 0267 (x0rro interview; KU0HN's tests: XARA HF 3,877/2,423
  bit/s at 5–6 % CPU against VARA's 5,817/1,203 at about 53 %).
- VARA level table: sigidwiki.com/wiki/VARA_HF. VarAC on SL1/SL2 decoding
  (−21/−18 dB on VARA's display scale): varac-hamradio.com, "adaptive
  broadcast speed".
- Mercury: github.com/Rhizomatica/mercury at `097271d`, especially
  `docs/MODES.md`, `CHANNEL-MODEL.md` (Watterson model, its bench, and the
  N5TW 2020 VARA/PACTOR comparison), `MFSK-PORT.md`, `FADE-CLIFF-DECISION.md`,
  `CAROUSEL-ARQ.md` and `RELEASE-1.9.21.md` (`mfsk_tx_only`).
- JS8: JS8Call-improved at `bad77ff`, `JS8_Mode/JS8Submode.cpp`, the user
  guide's sensitivity table, and `tools/decoder_comparison_benchmark.cpp`.
- FT8 thresholds: WSJT-X documentation (−21 dB) and N6MW's FT8 SNR analysis
  (−20.8 dB AWGN).
- EU law: Directive 2009/24/EC Arts. 5(3), 6 and 8; CJEU C-406/10, *SAS
  Institute v World Programming*.
