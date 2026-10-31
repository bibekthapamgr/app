"""pytest fixtures for the ProxyFlow M1 feasibility spike (Linux, root)."""
from __future__ import annotations

import json
import os
import platform
import subprocess
import sys
import time
from pathlib import Path

import pytest

SPIKE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SPIKE / "harness"))

from lab import Lab  # noqa: E402

RUN_ID = os.environ.get("SPIKE_RUN_ID") or time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
EVIDENCE = Path(os.environ.get("SPIKE_EVIDENCE_DIR", SPIKE / "evidence")) / f"run-{RUN_ID}"


class Checks:
    """Collects named checks so every result is recorded even if one fails."""

    def __init__(self, test_id: str, title: str, requirement: str, outdir: Path):
        self.data = {"test": test_id, "title": title, "requirement": requirement, "checks": [], "metrics": {},
                     "notes": []}
        self.out = outdir

    def check(self, name: str, ok: bool, detail=None):
        self.data["checks"].append({"check": name, "passed": bool(ok), "detail": detail})
        return ok

    def metric(self, **kv):
        self.data["metrics"].update(kv)

    def note(self, text: str):
        self.data["notes"].append(text)

    def finish(self):
        failed = [c for c in self.data["checks"] if not c["passed"]]
        self.data["verdict"] = "PASS" if not failed else "FAIL"
        (self.out / "verdict.json").write_text(json.dumps(self.data, indent=2, default=str))
        if failed:
            raise AssertionError("failed checks: " + "; ".join(f"{c['check']} ({c['detail']})" for c in failed))


@pytest.fixture(scope="session")
def lab():
    if os.geteuid() != 0:
        pytest.skip("the end-to-end spike needs root (network namespaces, TUN)")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    env = {
        "run_id": RUN_ID,
        "kernel": platform.release(),
        "python": platform.python_version(),
        "engine_binary": os.environ.get("PFSPIKE_BIN", str(SPIKE / "bin/pfspike")),
        "tools": {t: subprocess.run([t, "--version"], capture_output=True, text=True).stdout.splitlines()[:1]
                  for t in ("curl", "tcpdump", "iperf3", "stunnel4") if _which(t)},
    }
    if _which("go"):
        env["go"] = subprocess.run(["go", "version"], capture_output=True, text=True).stdout.strip()
    for cmd in (["squid", "-v"], ["danted", "-v"], ["tshark", "--version"]):
        r = subprocess.run(cmd, capture_output=True, text=True)
        env["tools"][cmd[0]] = (r.stdout or r.stderr).splitlines()[:1]
    (EVIDENCE / "environment.json").write_text(json.dumps(env, indent=2))
    L = Lab(EVIDENCE / "_lab")
    try:
        L.up()
        L.start_services()
        yield L
    finally:
        L.down()  # also on a failed setup: no stray proxies or namespaces left behind


def _which(t):
    from shutil import which
    return which(t)


@pytest.fixture
def ev(request) -> Path:
    d = EVIDENCE / request.node.name
    d.mkdir(parents=True, exist_ok=True)
    return d


@pytest.fixture
def checks(request, ev):
    doc = (request.node.function.__doc__ or "").strip().splitlines()
    title = doc[0] if doc else request.node.name
    req = next((ln.split(":", 1)[1].strip() for ln in doc if ln.strip().lower().startswith("requirement:")), "")
    return Checks(request.node.name, title, req, ev)
