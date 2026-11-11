#!/usr/bin/env python3
"""Frame-success sweeps for XARA and JS8 through Mercury's Watterson channel.

Pipeline per (system, mode, channel): clean bursts at the modem's rate ->
resample to 8 kHz -> watterson_test (preset, negligible noise) -> resample
back -> the decoder harness adds white noise for each SNR (average signal
power over noise in 2500 Hz, signal power taken from the unfaded reference
pass, so fading shows up as loss, as in CCIR/ITU testing).
"""
import os, subprocess, sys, json
from multiprocessing import Pool
import numpy as np
from scipy.signal import resample_poly, hilbert

FOFF_HZ = 10.0   # per-frame carrier offset, uniform +-FOFF_HZ
JS8_DT_S = 0.2   # per-frame JS8 start jitter, uniform 0..JS8_DT_S


def impair(x, fs, slot, seed, dt_max=0.0):
    """Per-slot random carrier offset (and optional start delay), same for ref and faded."""
    r = np.random.default_rng(seed)
    y = np.zeros_like(x)
    n = len(x) // slot
    for i in range(n):
        seg = x[i * slot:(i + 1) * slot]
        d = int(r.uniform(0, dt_max) * fs) if dt_max else 0
        if d:
            seg = np.concatenate([np.zeros(d), seg[:-d]])
        f = r.uniform(-FOFF_HZ, FOFF_HZ)
        t = np.arange(len(seg)) / fs
        y[i * slot:(i + 1) * slot] = np.real(hilbert(seg) * np.exp(2j * np.pi * f * t))
    return y

W = "/work"
WT = f"{W}/mercury/utils/watterson_test"
XSIM = f"{W}/target/release/xsim"
JS8SIM = f"{W}/js8sim"
G = 20000.0
CHANNELS = ["awgn", "ccir-good", "ccir-moderate", "ccir-poor", "itur-mdv"]


def ratio(fs):
    return {48000: (1, 6), 12000: (2, 3)}[fs]


def wt(src, dst, preset, seed):
    args = [WT, src, dst, "--No", "-150", "--ssbfilt", "0"]
    if preset != "awgn":
        args.append(f"--{preset}")
    env = dict(os.environ, MERCURY_WATTERSON_SEED=str(seed))
    r = subprocess.run(args, env=env, capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr)
    clip = [l for l in r.stderr.splitlines() if "clipped" in l]
    return clip[-1].strip() if clip else ""


def channel(clean_f32, fs, preset, seed, tag, slot=None, dt_max=0.0):
    x = np.fromfile(clean_f32, dtype="<f4").astype(np.float64)
    if slot:
        x = impair(x, fs, slot, seed + 77, dt_max)
    up, down = ratio(fs)
    x8 = resample_poly(x, up, down)
    s = f"{W}/tmp/{tag}.in.s16"
    np.clip(np.round(x8 * G), -32767, 32767).astype("<i2").tofile(s)
    out = {}
    for name, p in (("ref", "awgn"), ("faded", preset)):
        o = f"{W}/tmp/{tag}.{name}.s16"
        info = wt(s, o, p, seed)
        y = np.fromfile(o, dtype="<i2").astype(np.float64) / G
        y = resample_poly(y, down, up)[: len(x)]
        if len(y) < len(x):
            y = np.concatenate([y, np.zeros(len(x) - len(y))])
        f = f"{W}/tmp/{tag}.{name}.f32"
        y.astype("<f4").tofile(f)
        out[name] = (f, info)
        os.remove(o)
    os.remove(s)
    return out


JENV = dict(os.environ, LC_ALL="C")


def js8_point(a):
    mode, n, seed, faded, ref, snr = a
    r = subprocess.run([JS8SIM, "dec", mode, str(n), str(seed), faded, ref, "7", str(snr)],
                       capture_output=True, text=True, env=JENV)
    if r.returncode:
        return (snr, None, r.stderr[-500:])
    return (snr, r.stdout.strip().splitlines()[-1], "")


def run_xara(level, chan, n, snrs, bw="2300", seed=11):
    tag = f"x{bw}_{level}_{chan}"
    clean = f"{W}/tmp/{tag}.clean.f32"
    subprocess.run([XSIM, "gen", bw, str(level), str(n), str(seed), clean], check=True)
    slot = int(subprocess.run([XSIM, "info", bw, str(level), "1", "1"], capture_output=True, text=True).stdout.split()[0])
    ch = channel(clean, 48000, chan, seed + 1000, tag, slot)
    r = subprocess.run([XSIM, "dec", bw, str(level), str(n), str(seed), ch["faded"][0], ch["ref"][0], "5"]
                       + [str(s) for s in snrs], capture_output=True, text=True, check=True)
    rows = []
    for line in r.stdout.split():
        snr, ok, nn = line.split(",")
        rows.append(dict(sys="xara", bw=bw, mode=f"L{level}", chan=chan, snr=float(snr), ok=int(ok), n=int(nn), false=0,
                         clip=ch["faded"][1]))
    for f in (clean, ch["faded"][0], ch["ref"][0]):
        os.remove(f)
    return rows


def run_js8(mode, chan, n, snrs, pool, seed=11):
    tag = f"j_{mode}_{chan}"
    clean = f"{W}/tmp/{tag}.clean.f32"
    subprocess.run([JS8SIM, "gen", mode, str(n), str(seed), clean], check=True, env=JENV)
    nmax = int(subprocess.run([JS8SIM, "info", mode, "1", "1"], capture_output=True, text=True, env=JENV).stdout.split()[0])
    ch = channel(clean, 12000, chan, seed + 1000, tag, nmax, JS8_DT_S)
    rows = []
    for snr, line, err in pool.map(js8_point, [(mode, n, seed, ch["faded"][0], ch["ref"][0], s) for s in snrs]):
        if line is None:
            sys.exit(f"js8 {mode} {chan} {snr}: {err}")
        s, ok, nn, fl = line.split(",")
        rows.append(dict(sys="js8", bw="", mode=mode, chan=chan, snr=float(s), ok=int(ok), n=int(nn), false=int(fl),
                         clip=ch["faded"][1]))
    for f in (clean, ch["faded"][0], ch["ref"][0]):
        os.remove(f)
    return rows


def rng(a, b, step=1.0):
    return [round(a + i * step, 1) for i in range(int(round((b - a) / step)) + 1)]


if __name__ == "__main__":
    os.makedirs(f"{W}/tmp", exist_ok=True)
    plan = json.loads(sys.argv[1])
    out = sys.argv[2]
    with Pool(int(os.environ.get("JOBS", "15"))) as pool, open(out, "a") as fo:
        for job in plan:
            for chan in job.get("chans", CHANNELS):
                if job["sys"] == "xara":
                    rows = run_xara(job["level"], chan, job["n"], rng(*job["snr"]), job.get("bw", "2300"))
                else:
                    rows = run_js8(job["mode"], chan, job["n"], rng(*job["snr"]), pool)
                for r in rows:
                    fo.write(json.dumps(r) + "\n")
                fo.flush()
                print(job.get("level", job.get("mode")), chan,
                      " ".join(f"{r['snr']:g}:{r['ok']}" for r in rows), rows[0]["clip"], flush=True)
