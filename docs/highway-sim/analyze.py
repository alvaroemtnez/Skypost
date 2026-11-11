#!/usr/bin/env python3
"""Summarise results.jsonl + mresults.jsonl: thresholds and goodput envelopes."""
import json, math, sys
from collections import defaultdict

rows = []
for f in sys.argv[1:]:
    rows += [json.loads(l) for l in open(f)]

curves = defaultdict(dict)
for r in rows:
    curves[(r["sys"], r["mode"], r["chan"])][r["snr"]] = r["ok"] / r["n"]


def thr(c, p):
    """Lowest SNR above which success stays >= p (linear interpolation)."""
    s = sorted(c)
    for i in range(len(s) - 1, -1, -1):
        if c[s[i]] < p:
            if i == len(s) - 1:
                return None
            a, b = s[i], s[i + 1]
            return a + (p - c[a]) / (c[b] - c[a]) * (b - a)
    return s[0]  # never below p in range


# ---------- airtime models ----------
# JS8: 9-byte frame per slot (Skypost assumption), no per-frame ACK.
JS8 = {"E": ("Slow", 30), "A": ("Normal", 15), "B": ("Fast", 10), "C": ("JS8 40", 6)}

# XARA 2300: (kind, symbols/spf, sps, k_bits, flush_symbols)
R = 1152 / 1024
XL = {1: ("f", 125, 128, 96), 2: ("f", 229, 64, 200), 3: ("f", 229, 64, 402), 4: ("f", 396, 32, 736),
      5: ("p", 376, 32, 1080, 1), 6: ("p", 365, 32, 1410, 1), 7: ("p", 355, 32, 2076, 4), 8: ("p", 352, 32, 2754, 4),
      9: ("p", 349, 32, 3424, 2), 10: ("o", 150, 64, 7197), 11: ("o", 150, 64, 9596), 12: ("o", 150, 64, 11512),
      13: ("o", 150, 64, 14398), 14: ("o", 150, 64, 17276), 15: ("o", 155, 64, 21710), 16: ("o", 155, 64, 26056)}


def xara_frame(level):
    v = XL[level]
    kind, spf, sps, k = v[:4]
    flush = v[4] if kind == "p" else 0
    per = sps * (R if kind == "o" else 1) / 3000
    bps = {1: 3, 2: 3, 3: 4, 4: 4, 13: 3, 14: 3, 15: 4, 16: 5}.get(level, 2)
    lead = math.ceil((0.080 if bps < 4 else 0.120) / per - 1e-9)
    data = (spf - 1) * per
    return data, lead * per, flush * per, k // 8 - 3


def xara_cycle(level):
    data, lead, flush, payload = xara_frame(level)
    ack = 1.36 if level <= 4 else (0.72 if level <= 9 else 0.40)
    gaps = 0.55  # decode allowance + ack delay + PTT tail, from timing tables (approx.)
    return lead + 2 * (data + flush) + gaps + ack, 2 * payload


# Mercury: (frame seconds incl. ~0.66 s preamble/postamble, data bytes per frame after headers)
MER = {"MFSK103": (26.2, 92), "MFSK100": (13.5, 92), "DATAC15": (3.74 + 0.66, 24), "DATAC4": (5.17 + 0.66, 48),
       "DATAC3": (3.19 + 0.66, 120), "DATAC1": (4.18 + 0.66, 504), "DATAC17": (6.71 + 0.66, 1176)}
POLL = 3.74 + 0.6  # DATAC16 poll + gaps per round


def merc_cycle(mode):
    t, b = MER[mode]
    k = max(1, int(30 // t))  # frames per <=30 s round
    return POLL + k * t, k * b


def goodput(sysname, mode, p):
    if sysname == "js8":
        return p * 72 / JS8[mode][1]
    if sysname == "xara":
        T, B = xara_cycle(int(mode[1:]))
        return p * B * 8 / T
    T, B = merc_cycle(mode)
    return p * B * 8 / T


chans = ["awgn", "ccir-good", "ccir-moderate", "ccir-poor", "itur-mdv"]
print("## Thresholds: SNR (dB, average power, 2500 Hz) for 50 % / 90 % frame success\n")
print("| system | mode | " + " | ".join(chans) + " |")
print("|---|---|" + "---|" * len(chans))
order = lambda k: (k[0], {"E": 0, "A": 1, "B": 2, "C": 3, "I": 4}.get(k[1], 0), int(k[1][1:]) if k[1].startswith("L") else 0, k[1])
for key in sorted({(s, m) for s, m, _ in curves}, key=lambda k: order(k)):
    cells = []
    for ch in chans:
        c = curves.get((*key, ch))
        if not c:
            cells.append("")
            continue
        a, b = thr(c, 0.5), thr(c, 0.9)
        f = lambda v: "n/a" if v is None else f"{v:.1f}"
        cells.append(f"{f(a)} / {f(b)}")
    name = JS8[key[1]][0] if key[0] == "js8" else key[1]
    print(f"| {key[0]} | {name} | " + " | ".join(cells) + " |")

print("\n## Airtime model\n")
for m, (n, p) in JS8.items():
    print(f"- JS8 {n}: 9 B per {p} s slot = {72 / p:.1f} bit/s at 100 %")
for l in range(1, 17):
    T, B = xara_cycle(l)
    print(f"- XARA L{l}: {B} B per {T:.2f} s cycle (2 frames + ACK) = {B * 8 / T:.0f} bit/s at 100 %")
for m in MER:
    T, B = merc_cycle(m)
    print(f"- Mercury {m}: {B} B per {T:.1f} s round + poll = {B * 8 / T:.0f} bit/s at 100 %")


def envelope(sysname, modes, ch, snr):
    best, arg = 0.0, "-"
    for m in modes:
        c = curves.get((sysname, m, ch))
        if not c:
            continue
        s = sorted(c)
        if snr < s[0]:
            p = 0.0
        elif snr > s[-1]:
            p = c[s[-1]]
        else:
            lo = max(x for x in s if x <= snr)
            hi = min(x for x in s if x >= snr)
            p = c[lo] if hi == lo else c[lo] + (c[hi] - c[lo]) * (snr - lo) / (hi - lo)
        g = goodput(sysname, m, p)
        if g > best:
            best, arg = g, (JS8[m][0] if sysname == "js8" else m)
    return best, arg


SETS = {
    "JS8 (all speeds)": ("js8", list(JS8)),
    "XARA L1-4 (QDX tx)": ("xara", [f"L{i}" for i in range(1, 5)]),
    "Mercury MFSK (QDX tx)": ("mercury", ["MFSK100", "MFSK103"]),
    "XARA L1-16 (linear)": ("xara", [f"L{i}" for i in range(1, 17)]),
    "Mercury all rungs (linear)": ("mercury", list(MER)),
}
for ch in chans:
    print(f"\n## Best-mode goodput envelope, bit/s (mode), channel {ch}\n")
    print("| SNR | " + " | ".join(SETS) + " |")
    print("|---|" + "---|" * len(SETS))
    for snr in range(-26, 31, 2):
        cells = []
        for name, (sysname, modes) in SETS.items():
            g, m = envelope(sysname, modes, ch, snr)
            cells.append(f"{g:.0f} ({m})" if g >= 0.5 else "–")
        print(f"| {snr} | " + " | ".join(cells) + " |")
