#!/usr/bin/env python3
"""Mercury modes through the same channel and SNR convention as run.py.

MFSK (100 = 1 kHz floor, 103 = 500 Hz floor): utils/mfsk_burst_file, one
frame per slot, decoded by the real MFSK backend.
DATAC modes: codec2/FreeDV raw data tools vendored in Mercury, one burst
(preamble, frame, postamble) per frame, counted by matching payloads.
All native 8 kHz. Noise is added here: average in-burst signal power of the
unfaded reference over noise power in 2500 Hz.
"""
import json, os, re, subprocess, sys
from multiprocessing import Pool
import numpy as np
from run import W, G, CHANNELS, wt, rng, impair

MB = f"{W}/mercury/utils/mfsk_burst_file"
FD = f"{W}/mercury/modem/freedv"
FS = 8000.0


def on_mask(x):
    a = np.abs(x) > 0
    k = np.ones(64)
    return np.convolve(a.astype(float), k, mode="same") > 0


def pass_channel(s16, chan, seed, tag):
    out = {}
    for name, p in (("ref", "awgn"), ("faded", chan)):
        o = f"{W}/tmp/{tag}.{name}.s16"
        info = wt(s16, o, p, seed)
        out[name] = (np.fromfile(o, dtype="<i2").astype(np.float64) / G, info)
        os.remove(o)
    return out


def noisy(x, pref, snr, seed):
    sigma = np.sqrt(pref * (FS / 2) / (2500.0 * 10 ** (snr / 10)))
    r = np.random.default_rng(seed)
    return x + sigma * r.standard_normal(len(x))


def to_s16(x, path):
    np.clip(np.round(x * G), -32767, 32767).astype("<i2").tofile(path)


# ---------------- MFSK ----------------
def mfsk_point(a):
    mode, n, slot, faded, pref, snr = a
    x = np.fromfile(faded, dtype="<f8")
    ok = 0
    for t in range(n):
        p = f"{W}/tmp/m{mode}_{snr}_{t}.s16"
        to_s16(noisy(x[t * slot:(t + 1) * slot], pref, snr, 100000 + 1000 * t + int(snr * 10) + 77), p)
        r = subprocess.run([MB, "rx", str(mode), str(t + 1), p], capture_output=True, text=True)
        os.remove(p)
        ok += r.stdout.strip() == "1"
    return snr, ok


def run_mfsk(mode, chan, n, snrs, pool):
    tag = f"m{mode}_{chan}"
    parts = []
    for t in range(n):
        b, f = f"{W}/tmp/{tag}.b.raw", f"{W}/tmp/{tag}.f.raw"
        subprocess.run([MB, "tx", str(mode), str(t + 1), b, f], check=True, capture_output=True)
        parts.append(np.fromfile(f, dtype="<i2").astype(np.float64) / G)
    slot = len(parts[0])
    assert all(len(p) == slot for p in parts)
    x = np.concatenate(parts)
    x = impair(x, FS, slot, 4242)
    s16 = f"{W}/tmp/{tag}.in.s16"
    to_s16(x, s16)
    ch = pass_channel(s16, chan, 2011, tag)
    os.remove(s16)
    ref, faded = ch["ref"][0], ch["faded"][0]
    m = on_mask(x)
    pref = float(np.mean(ref[: len(m)][m[: len(ref)]] ** 2))
    fp = f"{W}/tmp/{tag}.faded.f8"
    faded.astype("<f8").tofile(fp)
    res = pool.map(mfsk_point, [(mode, n, slot, fp, pref, s) for s in snrs])
    os.remove(fp)
    return [dict(sys="mercury", bw="", mode=f"MFSK{mode}", chan=chan, snr=s, ok=o, n=n, false=0,
                 clip=ch["faded"][1]) for s, o in res]


# ---------------- DATAC ----------------
def datac_point(a):
    mode, n, faded, pref, snr, payloads, plen = a
    x = np.fromfile(faded, dtype="<f8")
    p = f"{W}/tmp/d{mode}_{snr}.s16"
    o = f"{W}/tmp/d{mode}_{snr}.out"
    to_s16(noisy(x, pref, snr, 100991 + int(snr * 10)), p)
    subprocess.run([f"{FD}/freedv_data_raw_rx", "--framesperburst", "1", mode, p, o], capture_output=True)
    got = open(o, "rb").read() if os.path.exists(o) else b""
    for f in (p, o):
        if os.path.exists(f):
            os.remove(f)
    want = set(payloads)
    ok = len({got[i:i + plen] for i in range(0, len(got) - plen + 1, plen)} & want)
    return snr, ok


def run_datac(mode, chan, n, snrs, pool):
    tag = f"d{mode}_{chan}"
    r = subprocess.run([f"{FD}/freedv_data_raw_tx", mode, "/dev/null", "/dev/null"], capture_output=True, text=True)
    plen = int(re.search(r"payload bytes_per_modem_frame: (\d+)", r.stderr).group(1))
    rg = np.random.default_rng(5)
    payloads = [bytes(rg.integers(0, 256, plen, dtype=np.uint8)) for _ in range(n)]
    pin, raw = f"{W}/tmp/{tag}.bin", f"{W}/tmp/{tag}.raw"
    open(pin, "wb").write(b"".join(payloads))
    subprocess.run([f"{FD}/freedv_data_raw_tx", "--framesperburst", "1", "--delay", "1000", mode, pin, raw],
                   check=True, capture_output=True)
    x = np.fromfile(raw, dtype="<i2").astype(np.float64)
    x = np.concatenate([np.zeros(4000), x, np.zeros(8000)])
    x *= 0.12 / np.sqrt(np.mean(x[on_mask(x)] ** 2))
    x = impair(x, FS, len(x), 4243)  # one constant offset for the whole DATAC stream
    s16 = f"{W}/tmp/{tag}.in.s16"
    to_s16(x, s16)
    ch = pass_channel(s16, chan, 2011, tag)
    for f in (pin, raw, s16):
        os.remove(f)
    ref, faded = ch["ref"][0], ch["faded"][0]
    m = on_mask(x)
    k = min(len(m), len(ref))
    pref = float(np.mean(ref[:k][m[:k]] ** 2))
    fp = f"{W}/tmp/{tag}.faded.f8"
    faded.astype("<f8").tofile(fp)
    res = pool.map(datac_point, [(mode, n, fp, pref, s, payloads, plen) for s in snrs])
    os.remove(fp)
    return [dict(sys="mercury", bw="", mode=mode, chan=chan, snr=s, ok=o, n=n, false=0, clip=ch["faded"][1])
            for s, o in res]


if __name__ == "__main__":
    os.makedirs(f"{W}/tmp", exist_ok=True)
    plan = json.loads(sys.argv[1])
    with Pool(int(os.environ.get("JOBS", "15"))) as pool, open(sys.argv[2], "a") as fo:
        for job in plan:
            for chan in job.get("chans", CHANNELS):
                f = run_mfsk if job["kind"] == "mfsk" else run_datac
                rows = f(job["mode"], chan, job["n"], rng(*job["snr"]), pool)
                for r in rows:
                    fo.write(json.dumps(r) + "\n")
                fo.flush()
                print(job["mode"], chan, " ".join(f"{r['snr']:g}:{r['ok']}" for r in rows), rows[0]["clip"], flush=True)
