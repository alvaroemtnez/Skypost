"""Thin wrapper around the Rust `nvl` CLI.

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

ACK_TYPE = 7
DUPLICATE_EXIT = 3


class NvlError(RuntimeError):
    pass


class Duplicate(NvlError):
    """Frame was already delivered; the receiver should re-ACK it."""


def find_binary(configured: str | None = None) -> str:
    candidates = [configured, os.environ.get("NVL_BIN")]
    root = Path(__file__).resolve().parents[2] / "core" / "target"
    candidates += [str(root / "release" / "nvl"), str(root / "debug" / "nvl"), "nvl"]
    for c in candidates:
        if not c:
            continue
        found = shutil.which(c) or (c if Path(c).is_file() else None)
        if found:
            return found
    raise NvlError("nvl binary not found: build it with `cargo build --release -p nvl-cli` in core/")


def b32decode(s: str) -> bytes:
    s = s.strip().upper()
    return base64.b32decode(s + "=" * (-len(s) % 8))


def is_ack(b32: str) -> bool:
    raw = b32decode(b32)
    return bool(raw) and (raw[0] >> 6) == 0 and (raw[0] & 0x1F) == ACK_TYPE


@dataclass
class Nvl:
    state: Path
    binary: str

    def _run(self, *args: str) -> str:
        p = subprocess.run([self.binary, *args], capture_output=True, text=True)
        if p.returncode == DUPLICATE_EXIT:
            raise Duplicate(p.stderr.strip())
        if p.returncode != 0:
            raise NvlError(p.stderr.strip() or f"nvl exited {p.returncode}")
        return p.stdout.strip()

    def encode(self, kind: str, *args: str, encrypt: bool = False, dst: int = 15) -> dict:
        flags = ["--state", str(self.state), "--dst", str(dst), "--format", "json"]
        if encrypt:
            flags.append("--encrypt")
        return json.loads(self._run("encode", *flags, kind, *args))

    def decode(self, b32: str) -> dict:
        return json.loads(self._run("decode", "--state", str(self.state), b32))

    def ack_encode(self, to: int, counter: int, snr: int, dt_ds: int) -> str:
        snr = max(-128, min(127, snr))
        dt_ds = max(-128, min(127, dt_ds))
        return self._run(
            "ack", "encode", "--state", str(self.state),
            "--to", str(to), "--counter", str(counter), "--snr", str(snr), "--dt", str(dt_ds),
        )

    def ack_decode(self, b32: str) -> dict:
        return json.loads(self._run("ack", "decode", "--state", str(self.state), b32))
