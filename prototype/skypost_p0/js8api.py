"""Minimal client for the JS8Call TCP API (newline-delimited JSON).

VERIFY against the JS8Call 3.x API before relying on it. Assumed here, from
JS8Call 2.x: TCP server on port 2442 (enable it in Settings > Reporting),
messages shaped {"type": ..., "value": ..., "params": {...}}, transmit with
type "TX.SEND_MESSAGE", directed receptions arrive as "RX.DIRECTED" with
params FROM, TO, TEXT, SNR and (maybe) TDRIFT in seconds.
"""

from __future__ import annotations

import json
import socket
from collections.abc import Iterator
from dataclasses import dataclass


@dataclass
class Event:
    type: str
    value: str
    params: dict


class Js8Api:
    def __init__(self, host: str = "127.0.0.1", port: int = 2442, timeout: float = 1.0):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.sock.settimeout(timeout)
        self._buf = b""

    def close(self) -> None:
        self.sock.close()

    def send(self, type_: str, value: str = "", **params) -> None:
        msg = {"type": type_, "value": value, "params": params}
        self.sock.sendall((json.dumps(msg) + "\n").encode())

    def transmit(self, text: str) -> None:
        """Queue text for transmission. JS8Call prepends our callsign."""
        self.send("TX.SEND_MESSAGE", text)

    def events(self) -> Iterator[Event | None]:
        """Yield events as they arrive; yields None on each idle timeout so the
        caller can check its own deadlines."""
        while True:
            try:
                chunk = self.sock.recv(65536)
            except TimeoutError:
                yield None
                continue
            if not chunk:
                raise ConnectionError("JS8Call closed the API connection")
            self._buf += chunk
            while b"\n" in self._buf:
                line, self._buf = self._buf.split(b"\n", 1)
                if not line.strip():
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                yield Event(d.get("type", ""), d.get("value", ""), d.get("params") or {})
