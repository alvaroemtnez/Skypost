"""Base side: listen through JS8Call, ACK every authentic frame, forward to
Matrix and ntfy, and log everything as JSON lines.

    SKYPOST_MATRIX_TOKEN=... python -m skypost_p0.base --config base.toml

ACKs go out signed (they carry no content). Replies from the family side and
store-and-forward are Phase 1 (pharos); Phase 0 is one-way plus ACKs.
"""

from __future__ import annotations

import argparse
import os
import sys

from .bridges import format_message, matrix_send, ntfy_send
from .common import dt_ds_of, load_config, log_event, snr_of
from .framing import extract, wrap
from .js8api import Js8Api
from .keryxcli import Duplicate, Keryx, KeryxError, find_binary, is_ack


def forward(cfg: dict, text: str, urgent: bool, log_path: str | None) -> None:
    bridges = cfg.get("bridges", {})
    if m := bridges.get("matrix"):
        try:
            matrix_send(m["homeserver"], m["room_id"], os.environ[m["access_token_env"]], text)
        except Exception as e:  # keep listening whatever the bridge does
            log_event(log_path, event="bridge_error", bridge="matrix", error=str(e))
    if n := bridges.get("ntfy"):
        try:
            ntfy_send(n["url"], text, urgent=urgent)
        except Exception as e:
            log_event(log_path, event="bridge_error", bridge="ntfy", error=str(e))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="skypost_p0.base")
    p.add_argument("--config", required=True)
    a = p.parse_args(argv)
    cfg = load_config(a.config)
    keryx = Keryx(cfg["station"]["state"], find_binary(cfg["station"].get("keryx_bin")))
    log_path = cfg.get("log", {}).get("path")
    js8 = Js8Api(cfg["js8call"]["host"], int(cfg["js8call"]["port"]))
    log_event(log_path, event="base_started", callsign=cfg["station"]["callsign"])
    for ev in js8.events():
        if ev is None or ev.type != "RX.DIRECTED":
            continue
        from_call = ev.params.get("FROM", "?")
        payload = extract(ev.params.get("TEXT", "") or ev.value)
        if not payload or is_ack(payload):
            continue
        snr, dt = snr_of(ev.params), dt_ds_of(ev.params)
        try:
            d = keryx.decode(payload)
            src, counter = d["header"]["src"], d["header"]["counter"]
            log_event(log_path, event="rx", from_call=from_call, src=src, counter=counter, snr=snr, dt_ds=dt,
                      encrypted=d["header"]["encrypted"], type=d["header"]["msg_type"])
            forward(cfg, format_message(d, from_call, snr), d["header"]["msg_type"] == "Sos", log_path)
        except Duplicate as e:
            # Sender missed our ACK. The CLI error names sender and counter.
            log_event(log_path, event="duplicate", from_call=from_call, detail=str(e))
            src, counter = _parse_dup(str(e))
            if src is None:
                continue
        except KeryxError as e:
            log_event(log_path, event="rx_rejected", from_call=from_call, snr=snr, error=str(e))
            continue
        ack = keryx.ack_encode(src, counter, snr if snr is not None else 0, dt)
        js8.transmit(wrap(ack, from_call))
        log_event(log_path, event="ack_tx", to=from_call, src=src, counter=counter)
    return 0


def _parse_dup(msg: str) -> tuple[int | None, int | None]:
    # "error: duplicate of an already delivered frame (sender 1, counter 0)"
    import re

    m = re.search(r"sender (\d+), counter (\d+)", msg)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)


if __name__ == "__main__":
    sys.exit(main())
