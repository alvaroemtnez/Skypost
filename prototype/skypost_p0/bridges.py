"""Bridges from the base station to the family side (Phase 0: Matrix, ntfy)."""

from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request


def matrix_send(homeserver: str, room_id: str, token: str, text: str) -> None:
    """Post a plain m.room.message (Matrix client-server API v3)."""
    txn = f"skypost{time.time_ns()}"
    room = urllib.parse.quote(room_id, safe="")
    url = f"{homeserver.rstrip('/')}/_matrix/client/v3/rooms/{room}/send/m.room.message/{txn}"
    body = json.dumps({"msgtype": "m.text", "body": text}).encode()
    req = urllib.request.Request(
        url, data=body, method="PUT",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        r.read()


def ntfy_send(url: str, text: str, urgent: bool = False) -> None:
    headers = {"Title": "Skypost"}
    if urgent:
        headers |= {"Priority": "urgent", "Tags": "sos"}
    req = urllib.request.Request(url, data=text.encode(), method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=20) as r:
        r.read()


def format_message(decoded: dict, from_call: str, snr: int | None) -> str:
    h = decoded["header"]
    lock = "cifrado" if h["encrypted"] else "firmado"
    snr_txt = f", {snr} dB" if snr is not None else ""
    return f"[{from_call} #{h['src']} {lock}{snr_txt}] {decoded['text']}"
