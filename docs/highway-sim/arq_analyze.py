#!/usr/bin/env python3
"""End-to-end ARQ comparison: XARA (arq.jsonl from arqrun.py) against Mercury's
own published bench (results/mercury-bench/throughput_old_vs_standard.csv,
from Rhizomatica/mercury docs/channel-figures, standard presets only).

SNR: in-burst SNR of the payload direction, average power over noise in
2500 Hz. Mercury's SNR3k is converted with +0.79 dB (3000 vs 2500 Hz).
Throughput: payload bits / time from link-up to the last byte delivered
(XARA also shown including connect). Unfinished XARA runs count as
delivered/elapsed (a lower bound). Interpolation is log-linear between the two
nearest measured points of the same channel and size, never extrapolated.
"""
import csv, json, math, sys
from collections import defaultdict

arq_path, merc_path = sys.argv[1], sys.argv[2]
POINTS = [-10, -5, 0, 5, 10, 15, 20, 25]
CHANS = ["awgn", "ccir-good", "ccir-moderate", "ccir-poor", "itur-mdv"]


def curve(pts):
    """pts: list of (snr, bps) -> sorted list of averaged points per 1 dB bucket"""
    b = defaultdict(list)
    for s, y in pts:
        b[round(s)].append((s, y))
    out = []
    for k in sorted(b):
        v = b[k]
        out.append((sum(s for s, _ in v) / len(v), sum(y for _, y in v) / len(v)))
    return out


def interp(c, x):
    for (x0, y0), (x1, y1) in zip(c, c[1:]):
        if x0 <= x <= x1 and y0 > 0 and y1 > 0:
            t = (x - x0) / (x1 - x0) if x1 > x0 else 0
            return math.exp(math.log(y0) + t * (math.log(y1) - math.log(y0)))
    return None


xara = defaultdict(list)
xara_total = defaultdict(list)
raw = defaultdict(list)
for l in open(arq_path):
    d = json.loads(l)
    if "error" in d or d["t_link"] is None:
        continue
    end = d["t_done"] if d["t_done"] is not None else d["t_end"]
    post = d["delivered"] * 8 / max(end - d["t_link"], 1e-9)
    tot = d["delivered"] * 8 / end
    key = (d["chan"], d["bytes"])
    xara[key].append((d["snr_inburst"], post))
    xara_total[key].append((d["snr_inburst"], tot))
    raw[key].append(d)

merc = defaultdict(list)
for r in csv.DictReader(open(merc_path)):
    if r["set"] != "sweep" or r["result"] != "pass" or r["arm"] not in CHANS:
        continue
    merc[(r["arm"], int(r["payload_bytes"]))].append((float(r["snr3k_inburst"]) + 0.79, float(r["bytes_per_s"]) * 8))

f = lambda v: "–" if v is None else f"{v:.0f}"
for nbytes in (4096, 32768):
    print(f"\n## {nbytes // 1024} KB transfer: throughput, bit/s, at in-burst SNR (dB, 2500 Hz)\n")
    print("| channel | modem | " + " | ".join(str(p) for p in POINTS) + " |")
    print("|---|---|" + "---|" * len(POINTS))
    for ch in CHANS:
        cx, ct, cm = curve(xara[(ch, nbytes)]), curve(xara_total[(ch, nbytes)]), curve(merc[(ch, nbytes)])
        print(f"| {ch} | XARA | " + " | ".join(f(interp(cx, p)) for p in POINTS) + " |")
        print(f"| {ch} | XARA incl. connect | " + " | ".join(f(interp(ct, p)) for p in POINTS) + " |")
        if cm:
            print(f"| {ch} | Mercury (own bench) | " + " | ".join(f(interp(cm, p)) for p in POINTS) + " |")
        else:
            print(f"| {ch} | Mercury (own bench) | " + " | ".join("n/m" for _ in POINTS) + " |")

print("\n## XARA runs per point (all channels): measured SNR, bit/s after link-up, done?, top level\n")
for key in sorted(raw):
    rows = sorted(raw[key], key=lambda d: d["snr_inburst"])
    cells = []
    for d in rows:
        end = d["t_done"] if d["t_done"] is not None else d["t_end"]
        bps = d["delivered"] * 8 / max(end - d["t_link"], 1e-9)
        cells.append(f"{d['snr_inburst']:.1f}:{bps:.0f}{'' if d['t_done'] is not None else '*'}/L{d['top_level']}")
    print(f"- {key[0]} {key[1]} B: " + "  ".join(cells))
print("\n(* = not finished within the time limit; rate = delivered/elapsed)")
