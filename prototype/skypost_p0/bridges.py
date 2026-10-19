"""Bridge from the base station to the family side.

Phase 0 forwards to Signal with one-shot `signal-cli send` calls (decision
0014). Do not run a signal-cli daemon for the same account meanwhile: both
lock the account.
"""

from __future__ import annotations

import subprocess


def signal_send(bin: str, account: str, group_id: str, text: str) -> None:
    """Send `text` to a Signal group as `account` (the base's own number)."""
    subprocess.run(
        [bin, "-a", account, "send", "-m", text, "-g", group_id],
        check=True, capture_output=True, text=True, timeout=120,
    )


def format_message(decoded: dict, from_call: str, snr: int | None) -> str:
    h = decoded["header"]
    lock = "cifrado" if h["encrypted"] else "firmado"
    snr_txt = f", {snr} dB" if snr is not None else ""
    return f"[{from_call} #{h['src']} {lock}{snr_txt}] {decoded['text']}"
