"""Phase 0 tests. The end-to-end test runs field and base against a fake
JS8Call "air" and the real Rust `keryx` binary (skipped if it is not built)."""

from __future__ import annotations

import json
import socket
import subprocess
import threading
from pathlib import Path

import pytest

from skypost_p0 import base, field
from skypost_p0.framing import extract, wrap
from skypost_p0.keryxcli import KeryxError, find_binary, is_ack


def test_framing_roundtrip_and_noise():
    assert extract("EA5AAA: " + wrap("ABCDEFGH234567", "ea5bbb")) == "ABCDEFGH234567"
    assert extract("EA5AAA: EA5BBB HOLA QUE TAL") is None
    assert extract("nv1 abcdefgh") == "ABCDEFGH"


def test_is_ack_by_version_and_type_bits():
    assert is_ack("M4HAF3YEHUCGEQA")  # 670E02EF043D046240, the v1 golden ACK
    assert not is_ack("IJYAAAIHUG6ZC4ESTP5IO")  # a v1 Canned frame
    assert not is_ack("A4AQF3YENDPUAWI")  # a v0 ACK


def keryx_or_skip() -> str:
    try:
        return find_binary()
    except KeryxError:
        pytest.skip("keryx binary not built")


class FakeAir:
    """Two fake JS8Call API servers: what one side transmits, the other hears."""

    def __init__(self, calls: dict[str, str]):
        self.calls = calls  # role -> callsign
        self.conns: dict[str, socket.socket] = {}
        self.servers = {}
        self.ports = {}
        for role in calls:
            s = socket.socket()
            s.bind(("127.0.0.1", 0))
            s.listen()
            self.servers[role] = s
            self.ports[role] = s.getsockname()[1]
            threading.Thread(target=self._serve, args=(role,), daemon=True).start()
        self.transmissions: list[tuple[str, str]] = []

    def _serve(self, role: str) -> None:
        conn, _ = self.servers[role].accept()
        self.conns[role] = conn
        buf = b""
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                msg = json.loads(line)
                if msg["type"] == "TX.SEND_MESSAGE":
                    self._deliver(role, msg["value"])

    def _deliver(self, sender: str, value: str) -> None:
        self.transmissions.append((sender, value))
        other = next(r for r in self.calls if r != sender)
        to, _, _ = value.partition(" ")
        ev = {
            "type": "RX.DIRECTED",
            "value": "",
            "params": {"FROM": self.calls[sender], "TO": to, "TEXT": f"{self.calls[sender]}: {value}",
                       "SNR": -15, "TDRIFT": 0.3},
        }
        if other in self.conns:
            self.conns[other].sendall((json.dumps(ev) + "\n").encode())


def write_cfg(path: Path, call: str, state: Path, port: int, peer: str) -> Path:
    path.write_text(
        f'[station]\ncallsign = "{call}"\nstate = "{state}"\n'
        f'[js8call]\nhost = "127.0.0.1"\nport = {port}\n'
        f'[peer]\nbase_callsign = "{peer}"\nbase = "casa"\n'
        f'[log]\npath = "{path.with_suffix(".jsonl")}"\n'
    )
    return path


def make_identities(keryx: str, tmp: Path) -> None:
    run = lambda *a: subprocess.run([keryx, *a], capture_output=True, text=True, check=True).stdout.strip()
    base_uri = run("keygen", "--alias", "casa", "--callsign", "EA5BAS", "--pharos", "--state", str(tmp / "base.json"))
    field_uri = run("keygen", "--alias", "ana", "--callsign", "EA5FLD", "--state", str(tmp / "field.json"))
    run("add", "--verified", "--state", str(tmp / "field.json"), base_uri)
    run("add", "--verified", "--state", str(tmp / "base.json"), field_uri)


def test_end_to_end_field_to_base_with_ack(tmp_path: Path):
    keryx = keryx_or_skip()
    make_identities(keryx, tmp_path)
    air = FakeAir({"field": "EA5FLD", "base": "EA5BAS"})
    base_cfg = write_cfg(tmp_path / "base.toml", "EA5BAS", tmp_path / "base.json", air.ports["base"], "EA5BAS")
    field_cfg = write_cfg(tmp_path / "field.toml", "EA5FLD", tmp_path / "field.json", air.ports["field"], "EA5BAS")

    threading.Thread(target=base.main, args=(["--config", str(base_cfg)],), daemon=True).start()
    while "base" not in air.conns:
        pass
    rc = field.main(["--config", str(field_cfg), "--timeout", "10", "text", "Hemos acampado junto al ibón"])
    assert rc == 0

    base_log = [json.loads(l) for l in (tmp_path / "base.jsonl").read_text().splitlines()]
    rx = next(e for e in base_log if e["event"] == "rx")
    assert (rx["src"], rx["counter"], rx["snr"], rx["dt_ds"]) == ("ana", 0, -15, 3)
    field_log = [json.loads(l) for l in (tmp_path / "field.jsonl").read_text().splitlines()]
    done = next(e for e in field_log if e["event"] == "delivered")
    assert done["base_heard_snr"] == -15
    assert any(is_ack(extract(v)) for s, v in air.transmissions if s == "base")


def test_encrypt_needs_explicit_opt_in(tmp_path: Path):
    cfg = write_cfg(tmp_path / "f.toml", "EA5FLD", tmp_path / "f.json", 1, "EA5BAS")
    assert field.main(["--config", str(cfg), "--encrypt", "sos"]) == 2


def test_duplicate_is_reported_with_who_to_reack(tmp_path: Path):
    from skypost_p0.keryxcli import Duplicate, Keryx

    keryx = keryx_or_skip()
    make_identities(keryx, tmp_path)
    f, b = Keryx(tmp_path / "field.json", keryx), Keryx(tmp_path / "base.json", keryx)
    frame = f.encode("text", "hola", to="casa")
    b.decode(frame["base32"])
    with pytest.raises(Duplicate) as e:
        b.decode(frame["base32"])
    assert e.value.info["counter"] == 0 and e.value.info["link"]["alias"] == "ana"


def test_forward_calls_signal_cli_and_logs_failures(tmp_path: Path):
    fake = tmp_path / "signal-cli"
    args_out = tmp_path / "args"
    fake.write_text(f'#!/bin/sh\nprintf "%s\\n" "$@" > {args_out}\n[ "$FAIL" = 1 ] && echo boom >&2 && exit 1\nexit 0\n')
    fake.chmod(0o755)
    cfg = {"bridges": {"signal": {"bin": str(fake), "account": "+34600000000", "group_id": "G=="}}}
    log = tmp_path / "log.jsonl"
    base.forward(cfg, "hola", urgent=True, log_path=str(log))
    assert args_out.read_text().splitlines() == ["-a", "+34600000000", "send", "-m", "🆘 SOS hola", "-g", "G=="]
    assert not log.exists()

    fake.write_text(fake.read_text().replace('[ "$FAIL" = 1 ] && ', ""))
    base.forward(cfg, "hola", urgent=False, log_path=str(log))
    ev = json.loads(log.read_text().splitlines()[-1])
    assert ev["event"] == "bridge_error" and ev["bridge"] == "signal" and ev["error"] == "boom"
