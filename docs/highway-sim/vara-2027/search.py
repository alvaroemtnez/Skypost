"""Drive vbsearch over legacy (control) and 2027 VARA tables."""
import struct
import subprocess
import sys
import time

APP = sys.argv[1]
BIN = sys.argv[2]
NV = 8  # values to match: 129^8 >> 2^24, so a hit is not chance


def u16s(path, off=0, n=200000):
    d = open(path, "rb").read()
    return list(struct.unpack(">%dH" % ((len(d) - off) // 2), d[off : off + 2 * ((len(d) - off) // 2)]))[:n]


def perm_prefix_len(v, start=0, maxlen=200000):
    """Smallest L such that v[start:start+L] is a permutation of 0..L-1."""
    seen = set()
    mx = -1
    for i in range(start, min(len(v), start + maxlen)):
        x = v[i]
        if x in seen:
            return None
        seen.add(x)
        mx = max(mx, x)
        if mx == len(seen) - 1 and len(seen) > 16:
            return len(seen)
    return None


def run(label, mode, m, vals):
    t = time.time()
    out = subprocess.run([BIN, mode, str(m)] + [str(x) for x in vals], capture_output=True, text=True).stdout
    print(f"{label:42s} {mode} m={m:<6d} -> {out.strip().replace(chr(10), ' | ')}  ({time.time() - t:.1f}s)")


# phase / byte tables: consecutive Int(Rnd*129)
for f in ["Data.dat", "Mod1.dat", "Mod2.dat"]:
    d = open(f"{APP}/{f}", "rb").read()
    run(f"{f} (bytes)", "seq", 129, d[:NV])
d = open(f"{APP}/Aleatorio.dat", "rb").read()
run("Aleatorio.dat (bytes, Int(Rnd*256))", "seq", 256, d[:NV])

# interleaver tables: first two permutations of record 0
for f, kind in [("Binary4.dat", "legacy 500"), ("Binary5.dat", "legacy 2300"),
                ("Binary1.dat", "2027 500"), ("Binary2.dat", "2027 2300")]:
    v = u16s(f"{APP}/{f}")
    l1 = perm_prefix_len(v, 0)
    l2 = perm_prefix_len(v, l1) if l1 else None
    print(f"{f} ({kind}): record 0 perms of length {l1} then {l2}")
    if l1:
        run(f"  {f} perm#1", "perm", l1, v[:NV])
    if l2:
        run(f"  {f} perm#2", "perm", l2, v[l1 : l1 + NV])
