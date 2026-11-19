"""Second pass over VARA's 2027 tables: more VB6 Rnd hypotheses, interleaver
quality and Aleatorio.dat. Needs the dumps written by dump.rs.

    python3 -I analyze.py WORK VBSEARCH2

WORK holds hf/out/app/ (the unpacked VARA HF installer) and dump/.
"""

import collections
import lzma
import math
import random
import statistics
import struct
import subprocess
import sys
import zlib

WORK, B = sys.argv[1], sys.argv[2]
APP = f"{WORK}/hf/out/app"


def f32(x):
    return struct.unpack("f", struct.pack("f", x))[0]


def run(label, args):
    out = subprocess.run([B] + [str(x) for x in args], capture_output=True, text=True).stdout
    print(f"{label:44s} -> {out.strip().replace(chr(10), ' | ')}")


def perms(path):
    d = {}
    for line in open(path):
        rec, kind, *vals = line.split()
        d[(rec, kind)] = list(map(int, vals))
    return d


def fy2(state, n):
    """Fisher-Yates (forward) driven by VB6 Rnd from a start state."""
    a = list(range(n))
    for i in range(n - 1):
        state = (state * 0xFD43FD + 0xC39EC3) & 0xFFFFFF
        j = i + int(f32((state / 16777216.0) * float(n - i)))
        a[i], a[j] = a[j], a[i]
    return a


def spread(p):
    """Largest S with |i-j| < S  =>  |p[i]-p[j]| >= S (the S-random criterion)."""
    n, s = len(p), 1
    while s <= n:
        s2 = s + 1
        for i in range(n):
            if any(abs(p[i] - p[j]) < s2 for j in range(i + 1, min(n, i + s2))):
                return s
        s = s2
    return s


def distinct_second_diffs(p):
    """QPP and other algebraic interleavers have very few; random ones about 1-1/e of n."""
    n = len(p)
    return len({(p[i + 2] - 2 * p[i + 1] + p[i]) % n for i in range(n - 2)})


m1, m2, data = (open(f"{APP}/{f}", "rb").read() for f in ("Mod1.dat", "Mod2.dat", "Data.dat"))

print("## VB6 Rnd hypotheses on the phase tables")
run("control: Data.dat, Int(Rnd*129)", ["stride", 1, 129] + list(data[:8]))
for k in (2, 3, 4):
    run(f"Mod1 Int(Rnd*129), every {k}th draw", ["stride", k, 129] + list(m1[:8]))
    run(f"Mod2 Int(Rnd*129), every {k}th draw", ["stride", k, 129] + list(m2[:8]))
run("Mod1 CInt(Rnd*128)", ["round", 128] + list(m1[:8]))
run("Mod2 CInt(Rnd*128)", ["round", 128] + list(m2[:8]))

print("## VB6 Rnd + Fisher-Yates on record 0 of 500 Hz")
ctrl = fy2(0x123456, 96)
run("control: fy2 shuffle made from state 0x123456", ["fy2", 96] + ctrl)
for name, path in (("legacy", "hf0_legacy.txt"), ("2027", "hf0_2027.txt")):
    p = perms(f"{WORK}/dump/{path}")
    for kind in ("turbo", "chan"):
        t = p[("R0", kind)]
        for m in ("fy1", "fy2", "fy3"):
            run(f"{name} R0 {kind} (n={len(t)}) {m}", [m, len(t)] + t)

print("## Turbo interleaver quality: S-spread (designed S-random would reach ~sqrt(K/2))")
print(f"{'bw':>5s} {'rec':>4s} {'K':>6s} {'legacy':>7s} {'2027':>5s} {'random':>7s} {'2nd-diff distinct/K (2027)':>27s}")
for i, bw in enumerate(("500", "2300", "2750")):
    old, new = perms(f"{WORK}/dump/hf{i}_legacy.txt"), perms(f"{WORK}/dump/hf{i}_2027.txt")
    for rec in sorted({r for r, _ in old}, key=lambda r: int(r[1:])):
        tl, tn = old[(rec, "turbo")], new[(rec, "turbo")]
        k = len(tl)
        if k > 3000:
            continue
        rnd = statistics.median(spread(random.sample(range(k), k)) for _ in range(20))
        print(f"{bw:>5s} {rec:>4s} {k:6d} {spread(tl):7d} {spread(tn):5d} {rnd:7} {distinct_second_diffs(tn) / k:27.3f}")

print("## Aleatorio.dat")
a = open(f"{APP}/Aleatorio.dat", "rb").read()
c = collections.Counter(a)
h = -sum(v / len(a) * math.log2(v / len(a)) for v in c.values())
print(f"{len(a)} B, entropy {h:.3f} bit/byte, zlib {len(zlib.compress(a, 9)) / len(a):.3f}, xz {len(lzma.compress(a)) / len(a):.3f}")
chance = 60000 * sum((v / len(a)) ** 2 for v in c.values())
lags = sorted(((sum(a[i] == a[i + lag] for i in range(60000)), lag) for lag in range(1, 600)), reverse=True)[:5]
print(f"byte repeats by lag (count, lag), top: {lags}; chance ~{chance:.0f}")
print("Mod1 == (Data + Aleatorio) mod 129 at", sum((data[i] + a[i]) % 129 == m1[i] for i in range(len(m1))), "of", len(m1), "(chance ~465)")
