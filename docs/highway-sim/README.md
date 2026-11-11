# Highway modem simulation

The harness behind [../highway-modems.md](../highway-modems.md). It measures
frame success against SNR for XARA (VARA HF protocol), JS8 and Mercury, all
through the same channel simulator, impairments and SNR convention. Research
tooling only: nothing here is shipped or linked into Skypost.

## What it does

```
clean frames at the modem's rate (XARA 48 kHz, JS8 12 kHz, Mercury 8 kHz)
  -> per-frame carrier offset, uniform ±10 Hz (JS8 also 0–200 ms start jitter)
  -> resample to 8 kHz
  -> Mercury's utils/watterson_test (validated ITU-R F.1487 / CCIR presets),
     negligible noise; a second pass with no fading path is the reference
  -> resample back
  -> white noise added by the decoder harness for each SNR
  -> the modem's own receiver decodes each frame; success = exact payload back
```

- **SNR:** average signal power during the frame (taken from the unfaded
  reference, so fading counts as loss, as in CCIR/ITU testing) over noise
  power in 2500 Hz. That is JS8's convention; Mercury and codec2 quote SNR3k,
  which reads 0.8 dB lower for the same signal.
- **Channels:** `awgn`, `ccir-good` (0.5 ms, 0.1 Hz), `ccir-moderate` (1 ms,
  0.5 Hz), `ccir-poor` (2 ms, 1 Hz), `itur-mdv` (ITU-R F.1487 mid-latitude
  disturbed NVIS: 7 ms, 1 Hz). Two equal Rayleigh paths, Gaussian Doppler.
- **Equal average power.** No PEP limit is modelled (see the study's
  caveats).

## Decoders under test

| System | Code used | Pinned at |
|---|---|---|
| XARA | `xara-phy` TX and `Receiver` (the shipped receiver), via `xsim/` | x0rro/xara 0.1.3 (`09a9201`) |
| JS8 | JS8Call-improved's own `JS8.cpp` decoder, via `js8sim.cpp` (after its `tools/decoder_comparison_benchmark.cpp`) | `bad77ff` (5 Nov 2026) |
| Mercury MFSK | `utils/mfsk_burst_file` (Mercury's real MFSK backend) | Rhizomatica/mercury `097271d` (11 Nov 2026) |
| Mercury DATAC | vendored codec2 `freedv_data_raw_tx/rx` (reference receiver, not Mercury's modem wrapper) | same |
| Channel | `utils/watterson_test` | same |

## Files

- `Containerfile`: Fedora 44 + gcc, Rust, Qt6Core, Boost, FFTW, numpy/scipy.
- `build.sh`: clones Mercury and JS8Call-improved at the pinned commits and
  builds everything (runs in the container).
- `podrun.sh`: runs a command in the container with `$WORK` at `/work`.
- `xsim/`: Rust harness for XARA (`gen`, `dec`, `info`).
- `js8sim.cpp`: C++ harness for JS8 (`gen`, `dec`, `info`).
- `run.py`: XARA and JS8 sweeps. `mrun.py`: Mercury sweeps.
- `analyze.py`: 50 %/90 % thresholds, airtime model, goodput envelopes.
- `xsim/src/bin/xarq.rs`: end-to-end XARA ARQ: two complete stations
  (`xara::station::hf::Station`, registered, compression off) through a
  streaming Watterson channel per direction (same model as Mercury's, at
  8 kHz; validated against `watterson_test` frame by frame, within a few
  percent). `xarq chan` is the offline mode used for that validation.
- `arqrun.py`: the ARQ sweep (4 KB × 3 seeds and 32 KB × 2 seeds, five
  channels, 2 dB steps; in-burst SNR measured on the payload direction, as
  Mercury's own bench does). `arq_analyze.py`: compares it with Mercury's
  published bench (`results/mercury-bench/`).
- `plans/`: the sweeps that produced `results/`.
- `results/arq.jsonl`, `results/arq-analysis.md`: end-to-end ARQ runs and
  the comparison with Mercury.
- `results/`: raw per-point results (`*.jsonl`, one line per system, mode,
  channel and SNR) and `analysis.md`. `results/v1-no-offsets/` is the first
  run, without carrier offsets or JS8 timing jitter, kept for comparison
  (thresholds within about 1 dB of the final run).

## Rerun

```sh
export WORK=/tmp/skypost-sim               # host directory (tmpfs is fine; ~2 GB peak)
mkdir -p $WORK && cp -r docs/highway-sim/. $WORK/
cp -a ~/Projectes/xara $WORK/xara-copy     # the XARA sources, as a copy
podman build -t localhost/skypost-modemsim -f $WORK/Containerfile $WORK
# inside the VSCodium flatpak, prefix with: PODMAN="flatpak-spawn --host podman"
$WORK/podrun.sh sh /work/build.sh
$WORK/podrun.sh python3 /work/run.py "$(cat $WORK/plans/xara-js8.json)" /work/results.jsonl
$WORK/podrun.sh python3 /work/mrun.py "$(cat $WORK/plans/mercury.json)" /work/mresults.jsonl
$WORK/podrun.sh python3 /work/analyze.py /work/results.jsonl /work/mresults.jsonl
$WORK/podrun.sh python3 /work/arqrun.py /work/arq.jsonl
python3 docs/highway-sim/arq_analyze.py $WORK/arq.jsonl docs/highway-sim/results/mercury-bench/throughput_old_vs_standard.csv
```

The frame-level sweeps take about an hour on 16 cores (JS8 Slow dominates);
the ARQ sweep about 40 minutes (simulation runs about 35× real time).

## Pitfalls found on the way

- **Mount a copy of XARA, not your clone.** `:z` relabels SELinux contexts
  of whatever it mounts. (`restorecon -RF <dir>` puts them back.)
- **Shared label (`:z`), not private (`:Z`),** when two containers use the
  work directory at once; `:Z` locks the first one out.
- **JS8Call-improved and the locale.** Under `LC_CTYPE=C.UTF-8` (which
  Python sets for child processes) the JS8 60 decoder stops decoding.
  `run.py` runs `js8sim` with `LC_ALL=C`. Other speeds are unaffected
  (checked).
- **JS8 60 fails with any carrier offset** (1/40 at 0 dB with ±10 Hz,
  40/40 without). Not used by Skypost; left out of the results.
- **JS8 is sensitive to frame timing on a perfect grid:** a fixed 16 ms
  shift moved JS8 40 by −1 dB and Normal by +0.7 dB. Hence the random start
  jitter. XARA was insensitive.
- **In-burst SNR in `xarq`:** noise power in 2500 Hz is
  σ² · 2500 / 24000 at 48 kHz. An early version had the ratio inverted
  (readings 19.6 dB low); fixed before the published run.
- **XARA needs an audio backend to compile** even when unused, hence the ALSA
  and Pulse development packages in the image.
- **Mercury's `freedv_data_raw_tx/rx` reject `--quiet`** (argument parsing
  breaks); don't pass it.
