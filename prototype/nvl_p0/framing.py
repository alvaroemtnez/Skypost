"""On-air text framing for Phase 0: `NV0 <base32 wire bytes>` inside a JS8
directed message. Base32 (A-Z, 2-7) fits JS8's character set."""

from __future__ import annotations

import re

MARKER = "NV0"
_RE = re.compile(r"\bNV0 ([A-Z2-7]{8,})\b")


def wrap(b32: str, to_call: str) -> str:
    return f"{to_call.upper()} {MARKER} {b32}"


def extract(text: str) -> str | None:
    """Return the base32 payload in a received JS8 text, if any."""
    m = _RE.search(text.upper())
    return m.group(1) if m else None
