from __future__ import annotations

import json
import time
import tomllib
from pathlib import Path


def load_config(path: str) -> dict:
    with open(path, "rb") as f:
        cfg = tomllib.load(f)
    cfg["station"]["state"] = str(Path(cfg["station"]["state"]).expanduser())
    return cfg


def log_event(path: str | None, **fields) -> None:
    """Append one JSON line. These logs are the Phase 0 propagation dataset."""
    rec = {"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), **fields}
    print(json.dumps(rec, ensure_ascii=False))
    if path:
        with open(Path(path).expanduser(), "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def snr_of(params: dict) -> int | None:
    v = params.get("SNR")
    return int(v) if isinstance(v, (int, float)) else None


def dt_ds_of(params: dict) -> int:
    v = params.get("TDRIFT")  # VERIFY: key name and unit (assumed seconds)
    return int(round(float(v) * 10)) if isinstance(v, (int, float)) else 0
