#!/usr/bin/env python3
"""End-to-end XARA ARQ sweep (xarq arq): two full stations, streaming Watterson
channel per direction, A sends BYTES to B. Method follows Mercury's bench
(docs/CHANNEL-MODEL.md there): SNR is the measured in-burst SNR of the payload
direction, throughput = payload / time. Prints one JSON line per run."""
import json, subprocess, sys, os
from multiprocessing import Pool

Q = "/work/target/release/xarq"
CHANNELS = ["awgn", "ccir-good", "ccir-moderate", "ccir-poor", "itur-mdv"]
# calibration: sigma 0.02 gave ~30.4 dB in-burst; the run reports the measured value
SIG0, SNR0 = 0.02, 30.4


def one(a):
    chan, snr, seed, nbytes, maxs = a
    sigma = SIG0 * 10 ** ((SNR0 - snr) / 20)
    r = subprocess.run([Q, "arq", chan, f"{sigma:.6g}", str(seed), str(nbytes), str(maxs)],
                       capture_output=True, text=True)
    if r.returncode:
        return json.dumps(dict(chan=chan, target=snr, seed=seed, bytes=nbytes, error=r.stderr[-300:]))
    d = json.loads(r.stdout.strip().splitlines()[-1])
    d["target"] = snr
    return json.dumps(d)


if __name__ == "__main__":
    out = sys.argv[1]
    jobs = []
    for nbytes, seeds, maxs in [(4096, [1, 2, 3], 1800), (32768, [1, 2], 3600)]:
        for chan in CHANNELS:
            for snr in range(-14, 27, 2):
                for s in seeds:
                    jobs.append((chan, snr, s, nbytes, maxs))
    jobs.sort(key=lambda j: j[1])  # low SNR (slow) runs interleaved early
    with Pool(int(os.environ.get("JOBS", "15"))) as p, open(out, "a") as fo:
        for line in p.imap_unordered(one, jobs):
            fo.write(line + "\n")
            fo.flush()
