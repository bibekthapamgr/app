"""Engine runners (ProxyFlow spike engine and hev-socks5-tunnel baseline)."""
from __future__ import annotations

import contextlib
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
from pathlib import Path

from lab import DEV, FAKE_DNS, IPV6, MARK, TUN_IP, TUN_IP6, UNDERLAY_DNS

SPIKE = Path(__file__).resolve().parent.parent


class SpikeEngine:
    """Runs cmd/pfspike inside the 'phone' namespace on tun0."""

    def __init__(self, outdir: Path, proxy: str, user: str = "", password: str = "", sni: str = "",
                 ca: Path | None = None, proxy_dns: bool = False, extra: list[str] | None = None,
                 binary: Path | None = None):
        self.out = Path(outdir)
        self.out.mkdir(parents=True, exist_ok=True)
        self.binary = binary or Path(os.environ.get("PFSPIKE_BIN", SPIKE / "bin/pfspike"))
        self.args = ["--tun", "tun0", "--proxy", proxy, "--mark", str(MARK),
                     "--stats", str(self.out / "engine-stats.json"), "--events", str(self.out / "engine-events.jsonl"),
                     "--ready", str(self.out / "engine.ready")]
        if sni:
            self.args += ["--sni", sni]
        if ca:
            self.args += ["--ca", str(ca)]
        if proxy_dns:
            self.args += ["--proxy-dns", f"{UNDERLAY_DNS}:53"]
        self.args += extra or []
        self.env = {**os.environ, "PF_PROXY_USERNAME": user, "PF_PROXY_PASSWORD": password}
        self.proc: subprocess.Popen | None = None
        self.final_stats: dict = {}

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    def start(self):
        ready = self.out / "engine.ready"
        ready.unlink(missing_ok=True)
        log = open(self.out / "engine-stdout.log", "ab")
        self.proc = subprocess.Popen(["ip", "netns", "exec", DEV, str(self.binary), *self.args],
                                     stdout=log, stderr=subprocess.STDOUT, env=self.env, start_new_session=True)
        deadline = time.time() + 10
        while time.time() < deadline:
            if ready.exists():
                time.sleep(0.2)
                return
            if self.proc.poll() is not None:
                raise RuntimeError(f"engine exited early: {(self.out / 'engine-stdout.log').read_text()[-2000:]}")
            time.sleep(0.05)
        raise RuntimeError("engine not ready")

    def pid(self) -> int:
        # The engine is the child of `ip netns exec` (exec'd, same pid).
        return self.proc.pid

    def stats(self) -> dict:
        p = self.out / "engine-stats.json"
        with contextlib.suppress(FileNotFoundError, json.JSONDecodeError):
            return json.loads(p.read_text())
        return {}

    def kill(self):
        """Simulate a crash: SIGKILL, no cleanup."""
        if self.proc and self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGKILL)
            self.proc.wait(timeout=5)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.proc.wait(timeout=15)
            time.sleep(0.6)  # final stats are written just before exit
        self.final_stats = self.stats()
        return self.final_stats


class HevEngine:
    """hev-socks5-tunnel baseline on the same tun0 (SOCKS5 upstream only)."""

    def __init__(self, outdir: Path, socks_host: str, socks_port: int, binary: Path, user: str = "", password: str = "",
                 mapdns: bool = True, mtu: int = 1500, offload: bool = False):
        self.out = Path(outdir)
        self.out.mkdir(parents=True, exist_ok=True)
        self.binary = binary
        auth = f"  username: '{user}'\n  password: '{password}'\n" if user else ""
        mdns = (f"mapdns:\n  address: {FAKE_DNS}\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n"
                f"  cache-size: 10000\n") if mapdns else ""
        # The real config (it holds the proxy password) lives in a private temp
        # dir; evidence gets a copy with the password redacted.
        self._private = Path(tempfile.mkdtemp(prefix="pf-hev-"))
        self.conf = self._private / "hev.yml"
        v6 = f"  ipv6: '{TUN_IP6}'\n" if IPV6 else ""
        # Android's VpnService fd has no IFF_VNET_HDR, so TSO/USO are not
        # available there; 'jumbo' is also disabled for a like-for-like test.
        off = "" if offload else "  offload:\n    tso: false\n    uso: false\n    jumbo: false\n"
        self.conf.write_text(f"""tunnel:
  name: tun0
  mtu: {mtu}
  ipv4: {TUN_IP}
{v6}  icmp: 'off'
{off}socks5:
  port: {socks_port}
  address: {socks_host}
  udp: 'udp'
  mark: {MARK}
{auth}{mdns}misc:
  log-file: {self.out / 'hev.log'}
  log-level: warn
""")
        os.chmod(self.conf, 0o600)
        redacted = re.sub(r"(?m)^(  password: ).*$", r"\1'<redacted>'", self.conf.read_text())
        (self.out / "hev.yml").write_text(redacted)
        self.proc = None

    def __enter__(self):
        log = open(self.out / "hev-stdout.log", "ab")
        self.proc = subprocess.Popen(["ip", "netns", "exec", DEV, str(self.binary), str(self.conf)],
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(1.0)
        if self.proc.poll() is not None:
            raise RuntimeError("hev exited: " + (self.out / "hev-stdout.log").read_text()[-2000:])
        return self

    def pid(self) -> int:
        return self.proc.pid

    def __exit__(self, *exc):
        if self.proc and self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.proc.wait(timeout=10)
        shutil.rmtree(self._private, ignore_errors=True)


def rss_kib(pid: int) -> int:
    with contextlib.suppress(FileNotFoundError):
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1])
    return -1


def cpu_ticks(pid: int) -> int:
    with contextlib.suppress(FileNotFoundError):
        f = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return int(f[11]) + int(f[12])  # utime + stime
    return -1
