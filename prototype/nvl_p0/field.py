"""Field side: send one message through JS8Call and wait for the base's ACK.

    python -m nvl_p0.field --config field.toml text "Hemos acampado"
    python -m nvl_p0.field --config field.toml ok --lat 42.63 --lon 0.66
    python -m nvl_p0.field --config field.toml sos
    python -m nvl_p0.field --config field.toml poll

Signed plaintext by default. `--encrypt` also needs `--dummy-load` (bench
tests) or `--emergency` (a real emergency): see docs/regulatory-es.md.
"""

from __future__ import annotations

import argparse
import sys
import time

from .common import dt_ds_of, load_config, log_event, snr_of
from .framing import extract, wrap
from .js8api import Js8Api
from .nvlcli import Nvl, NvlError, find_binary, is_ack


def build_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="nvl_p0.field")
    p.add_argument("--config", required=True)
    p.add_argument("--encrypt", action="store_true")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--dummy-load", action="store_true", help="I am transmitting into a dummy load")
    g.add_argument("--emergency", action="store_true", help="real emergency (ITU RR distress provision)")
    p.add_argument("--timeout", type=int, default=180, help="seconds to wait for each ACK")
    p.add_argument("--attempts", type=int, default=3)
    sub = p.add_subparsers(dest="kind", required=True)
    sub.add_parser("text").add_argument("text")
    sub.add_parser("canned").add_argument("id")
    for k in ("ok", "sos", "pos"):
        sp = sub.add_parser(k)
        sp.add_argument("--lat")
        sp.add_argument("--lon")
    sub.add_parser("poll")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    a = build_args(argv)
    cfg = load_config(a.config)
    if a.encrypt and not (a.dummy_load or a.emergency or cfg.get("safety", {}).get("allow_encrypted_tx")):
        print("refusing: --encrypt needs --dummy-load or --emergency (see docs/regulatory-es.md)", file=sys.stderr)
        return 2
    nvl = Nvl(cfg["station"]["state"], find_binary(cfg["station"].get("nvl_bin")))
    extra: list[str] = []
    if a.kind == "text":
        extra = [a.text]
    elif a.kind == "canned":
        extra = [a.id]
    elif a.kind in ("ok", "sos", "pos") and a.lat is not None:
        extra = ["--lat", a.lat, "--lon", a.lon]
    frame = nvl.encode(a.kind, *extra, encrypt=a.encrypt)
    base_call = cfg["peer"]["base_callsign"]
    log_path = cfg.get("log", {}).get("path")
    js8 = Js8Api(cfg["js8call"]["host"], int(cfg["js8call"]["port"]))
    try:
        for attempt in range(1, a.attempts + 1):
            js8.transmit(wrap(frame["base32"], base_call))
            log_event(log_path, event="tx", attempt=attempt, counter=frame["counter"], bytes=frame["bytes"],
                      js8_frames=frame["js8_frames"], encrypted=a.encrypt, kind=a.kind)
            deadline = time.monotonic() + a.timeout
            for ev in js8.events():
                if time.monotonic() > deadline:
                    break
                if ev is None or ev.type != "RX.DIRECTED":
                    continue
                payload = extract(ev.params.get("TEXT", "") or ev.value)
                if not payload or not is_ack(payload):
                    continue
                try:
                    ack = nvl.ack_decode(payload)
                except NvlError as e:
                    log_event(log_path, event="bad_ack", error=str(e))
                    continue
                if ack["acked_counter_lo"] == frame["counter"] % 256:
                    log_event(log_path, event="delivered", counter=frame["counter"], attempt=attempt,
                              base_heard_snr=ack["snr_db"], base_dt_ds=ack["dt_ds"],
                              we_heard_snr=snr_of(ev.params), we_dt_ds=dt_ds_of(ev.params))
                    return 0
        log_event(log_path, event="failed", counter=frame["counter"])
        return 1
    finally:
        js8.close()


if __name__ == "__main__":
    sys.exit(main())
