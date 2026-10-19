"""Thin wrapper around the Rust `keryx` CLI.

All protocol work (codec, crypto, ACK format) happens in Rust so the
prototype exercises the real wire format. This module only runs the binary.
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

VERSION = 1
ACK_TYPE = 7
DUPLICATE_EXIT = 3


class KeryxError(RuntimeError):
    pass


class Duplicate(KeryxError):
    """Frame was already delivered; the receiver should re-ACK it."""

    def __init__(self, msg: str, info: dict):
        super().__init__(msg)
        self.info = info  # {"sender": key, "counter": n, "link": {...}}


def find_binary(configured: str | None = None) -> str:
    candidates = [configured, os.environ.get("KERYX_BIN")]
    root = Path(__file__).resolve().parents[2] / "core" / "target"
    candidates += [str(root / "release" / "keryx"), str(root / "debug" / "keryx"), "keryx"]
    for c in candidates:
        if not c:
            continue
        found = shutil.which(c) or (c if Path(c).is_file() else None)
        if found:
            return found
    raise KeryxError("keryx binary not found: build it with `cargo build --release -p keryx-cli` in core/")


def b32decode(s: str) -> bytes:
    s = s.strip().upper()
    return base64.b32decode(s + "=" * (-len(s) % 8))


def is_ack(b32: str) -> bool:
    raw = b32decode(b32)
    return bool(raw) and (raw[0] >> 6) == VERSION and (raw[0] & 0x1F) == ACK_TYPE


@dataclass
class Keryx:
    state: Path
    binary: str

    def _run(self, *args: str) -> str:
        p = subprocess.run([self.binary, *args], capture_output=True, text=True)
        if p.returncode == DUPLICATE_EXIT:
            raise Duplicate(p.stderr.strip(), json.loads(p.stdout))
        if p.returncode != 0:
            raise KeryxError(p.stderr.strip() or f"keryx exited {p.returncode}")
        return p.stdout.strip()

    def encode(self, kind: str, *args: str, to: str, encrypt: bool = False) -> dict:
        flags = ["--state", str(self.state), "--to", to, "--format", "json"]
        if encrypt:
            flags.append("--encrypt")
        return json.loads(self._run("encode", *flags, kind, *args))

    def decode(self, b32: str) -> dict:
        return json.loads(self._run("decode", "--state", str(self.state), b32))

    def ack_encode(self, to: str, counter: int, snr: int, dt_ds: int) -> str:
        snr = max(-128, min(127, snr))
        dt_ds = max(-128, min(127, dt_ds))
        return self._run(
            "ack", "encode", "--state", str(self.state),
            "--to", to, "--counter", str(counter), "--snr", str(snr), "--dt", str(dt_ds),
        )

    def ack_check(self, to: str, counter: int, b32: str) -> dict:
        return json.loads(self._run(
            "ack", "check", "--state", str(self.state), "--to", to, "--counter", str(counter), b32,
        ))
