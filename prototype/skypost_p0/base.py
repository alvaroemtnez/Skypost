"""Base side: listen through JS8Call, ACK every authentic frame, forward to
Signal, and log everything as JSON lines.

    python -m skypost_p0.base --config base.toml

ACKs go out signed (they carry no content). Replies from the family side and
store-and-forward are Phase 1 (pharos); Phase 0 is one-way plus ACKs.
"""

from __future__ import annotations

import argparse
import subprocess
import sys

from .bridges import format_message, signal_send
from .common import dt_ds_of, load_config, log_event, snr_of
from .framing import extract, wrap
from .js8api import Js8Api
from .keryxcli import Duplicate, Keryx, KeryxError, find_binary, is_ack


def forward(cfg: dict, text: str, urgent: bool, log_path: str | None) -> None:
    if sg := cfg.get("bridges", {}).get("signal"):
        if urgent:
            text = f"🆘 SOS {text}"
        try:
            signal_send(sg.get("bin", "signal-cli"), sg["account"], sg["group_id"], text)
        except subprocess.CalledProcessError as e:  # keep listening whatever the bridge does
            log_event(log_path, event="bridge_error", bridge="signal", error=e.stderr.strip())
        except Exception as e:
            log_event(log_path, event="bridge_error", bridge="signal", error=str(e))


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
            if d["kind"] != "message":
                log_event(log_path, event="rx_unhandled", from_call=from_call, kind=d["kind"])
                continue
            sender, counter = d["sender"], d["header"]["counter"]
            log_event(log_path, event="rx", from_call=from_call, src=d["link"].get("alias"), sender=sender,
                      counter=counter, snr=snr, dt_ds=dt, encrypted=d["header"]["encrypted"],
                      type=d["header"]["msg_type"])
            forward(cfg, format_message(d, from_call, snr), d["header"]["msg_type"] == "Sos", log_path)
        except Duplicate as e:
            # Sender missed our ACK: ACK again, deliver nothing.
            sender, counter = e.info["sender"], e.info["counter"]
            log_event(log_path, event="duplicate", from_call=from_call, sender=sender, counter=counter)
        except KeryxError as e:
            log_event(log_path, event="rx_rejected", from_call=from_call, snr=snr, error=str(e))
            continue
        ack = keryx.ack_encode(sender, counter, snr if snr is not None else 0, dt)
        js8.transmit(wrap(ack, from_call))
        log_event(log_path, event="ack_tx", to=from_call, sender=sender, counter=counter)
    return 0


if __name__ == "__main__":
    sys.exit(main())
