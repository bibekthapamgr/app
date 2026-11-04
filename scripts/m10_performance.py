#!/usr/bin/env python3
"""Reports M10's performance baseline for one API level after the device tests (CI device job), beside the M5–M8 evidence.

PerformanceEndToEndTest (app/src/androidTest/.../e2e) logs each measurement as one logcat line under the tag ProxyFlowPerf,
"PF-PERF <name> key=value ...". This reads them from the device tests' logcat, sets each against the V1 quality bars
(docs/m1/01-scope.md §7) and writes them as notices and as m10-performance.json, from which
docs/m10/performance-baseline.md is made.

The bars are a phone's. An emulator on a CI runner shares the runner's CPU with the lab's proxies and has its own network,
so a figure past its bar is reported (a warning), not failed. What fails: a measurement that is missing (the test did not
run, or stopped part way), and a connection that opened while the proxy refused every one — it would have gone around it.

Usage: m10_performance.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m5_evidence import annotate  # noqa: E402
from m6_evidence import read  # noqa: E402

PERF = re.compile(r"ProxyFlowPerf\s*:\s*PF-PERF (\w+)((?: [\w.]+=\S*)*)\s*$")

PROTOCOLS = ("socks5", "http")
NAMES = {"socks5": "SOCKS5", "http": "HTTP"}

# What PerformanceEndToEndTest logs when every test ran to the end: (measurement, the fields that tell its lines apart).
EXPECTED = (
    [("connect", {"protocol": p}) for p in PROTOCOLS]
    + [("setup", {"protocol": p}) for p in PROTOCOLS]
    + [("fail_closed", {"proxy": proxy}) for proxy in ("refusing", "silent")]
    + [("throughput", {"protocol": p, "direction": d, "path": path}) for p in PROTOCOLS for d in ("down", "up") for path in ("proxy_only", "vpn")]
    + [("memory", {}), ("idle", {})]
)

# The V1 quality bars (docs/m1/01-scope.md §7), as the emulator's figures are set against them.
CONNECT_P90_MS = 3000
FAIL_CLOSED_MS = 1000
DOWN_MBIT_S = 100.0
CPU_PCT_AT_50_MBIT = 50.0
ENGINE_KIB_AT_200 = 50 * 1024
CONNECTIONS = 200


def measurements(logcat: list[str]) -> list[dict[str, str]]:
    """Each PF-PERF line as its name and fields, in logcat's order."""
    found = []
    for line in logcat:
        m = PERF.search(line)
        if m:
            fields = dict(pair.split("=", 1) for pair in m.group(2).split())
            found.append({"name": m.group(1), **fields})
    return found


def find(found: list[dict[str, str]], name: str, **keys: str) -> dict[str, str] | None:
    """The last line of [name] whose fields match [keys]."""
    matches = [m for m in found if m["name"] == name and all(m.get(k) == v for k, v in keys.items())]
    return matches[-1] if matches else None


def number(value: str | None) -> float | None:
    try:
        return float(value) if value is not None else None
    except ValueError:
        return None


def mib(kib: float | None) -> str:
    return "?" if kib is None else f"{kib / 1024:.1f} MiB"


def bar(name: str, target: str, value: str, met: bool | None) -> dict[str, object]:
    return {"bar": name, "target": target, "value": value, "met": met}


def verdict(met: bool | None) -> str:
    return {True: "within", False: "PAST", None: "not measured"}[met]


def report(found: list[dict[str, str]]) -> tuple[list[str], list[dict[str, object]], list[str]]:
    """The facts for the notice, the bars with each figure set against them, and the problems that fail the step."""
    facts: list[str] = []
    bars: list[dict[str, object]] = []
    problems: list[str] = []
    for name, keys in EXPECTED:
        if find(found, name, **keys) is None:
            label = " ".join([name] + [f"{k}={v}" for k, v in keys.items()])
            problems.append(f"no \"{label}\" measurement in logcat: PerformanceEndToEndTest did not run, or stopped before it")

    # Time to Connected.
    parts, p90s = [], []
    for protocol in PROTOCOLS:
        m = find(found, "connect", protocol=protocol)
        if m:
            p90 = number(m.get("p90_ms"))
            p90s.append(p90)
            drops = f", {m.get('uplink_drops')} left out (the emulator's uplink dropped)" if m.get("uplink_drops") not in (None, "0") else ""
            parts.append(f"{NAMES[protocol]} p50 {m.get('p50_ms')} ms, p90 {m.get('p90_ms')} ms, max {m.get('max_ms')} ms (n={m.get('n')}{drops})")
    if parts:
        facts.append("time to Connected, from the tap: " + "; ".join(parts))
    worst = max((p for p in p90s if p is not None), default=None)
    bars.append(bar("Time to connected", f"≤ {CONNECT_P90_MS} ms at p90", "?" if worst is None else f"{worst:.0f} ms (the slower protocol's p90)",
                    None if worst is None else worst <= CONNECT_P90_MS))

    # A new connection's set-up through the proxy.
    parts = []
    for protocol in PROTOCOLS:
        m = find(found, "setup", protocol=protocol)
        if m:
            parts.append(f"{NAMES[protocol]} p50 {m.get('p50_ms')} ms, p90 {m.get('p90_ms')} ms, max {m.get('max_ms')} ms (n={m.get('n')})")
    if parts:
        facts.append("a new connection's set-up through the proxy (the app's SYN to its SYN-ACK): " + "; ".join(parts))

    # Fail closed.
    refusing, silent = find(found, "fail_closed", proxy="refusing"), find(found, "fail_closed", proxy="silent")
    first = number(refusing.get("first_ms")) if refusing else None
    if refusing:
        opened = int(number(refusing.get("opened")) or 0)
        facts.append(
            f"fail closed, the proxy refusing: the first new connection failed after {refusing.get('first_ms')} ms; "
            f"{opened} of {len(refusing.get('results', '').split(','))} opened; each: {refusing.get('results')}; "
            f"the session then: {refusing.get('status')}"
        )
        if opened:
            problems.append(f"{opened} connection(s) opened while the proxy refused every connection: they went around it ({refusing.get('results')})")
    if silent:
        facts.append(f"fail closed, the proxy accepting and never answering: the connection failed after {silent.get('first_ms')} ms (the handshake timeout); "
                     f"the session then: {silent.get('status')}")
    bars.append(bar("Fail-closed reaction when the proxy dies", f"new connections refused in < {FAIL_CLOSED_MS} ms (no direct packet: the M6 evidence's captures)",
                    "?" if first is None else f"{first:.0f} ms", None if first is None else first < FAIL_CLOSED_MS))

    # Throughput, and the CPU it costs.
    down, cpu = [], []
    for direction in ("down", "up"):
        parts = []
        for protocol in PROTOCOLS:
            vpn = find(found, "throughput", protocol=protocol, direction=direction, path="vpn")
            only = find(found, "throughput", protocol=protocol, direction=direction, path="proxy_only")
            if not vpn:
                continue
            rate, path = number(vpn.get("mbit_s")), number(only.get("mbit_s")) if only else None
            share = f" ({rate / path * 100:.0f} % of it)" if rate is not None and path else ""
            parts.append(
                f"{NAMES[protocol]} {vpn.get('mbit_s')} Mbit/s through ProxyFlow, {only.get('mbit_s') if only else '?'} straight through the proxy{share}; "
                f"CPU {vpn.get('cpu_s_per_gb')} s per GB, {vpn.get('cpu_pct_at_50mbit')} % of one core at 50 Mbit/s"
            )
            if direction == "down" and rate is not None:
                down.append(rate)
            if number(vpn.get("cpu_pct_at_50mbit")) is not None:
                cpu.append(number(vpn.get("cpu_pct_at_50mbit")))
        if parts:
            facts.append(f"throughput {direction} ({vpn_bytes(found)}): " + "; ".join(parts))
    slowest = min(down, default=None)
    bars.append(bar("Throughput down through a local test proxy", f"≥ {DOWN_MBIT_S:.0f} Mbit/s", "?" if slowest is None else f"{slowest:.1f} Mbit/s (the slower protocol)",
                    None if slowest is None else slowest >= DOWN_MBIT_S))
    costliest = max(cpu, default=None)
    bars.append(bar("CPU at 50 Mbit/s", f"< {CPU_PCT_AT_50_MBIT:.0f} % of one core", "?" if costliest is None else f"{costliest:.1f} % (the costliest transfer)",
                    None if costliest is None else costliest < CPU_PCT_AT_50_MBIT))

    # Memory with 200 connections open.
    memory = find(found, "memory")
    held = number(memory.get("go_held_kib")) if memory else None
    connections = int(number(memory.get("connections")) or 0) if memory else 0
    if memory:
        peak, idle = number(memory.get("rss_peak_kib")), number(memory.get("rss_idle_kib"))
        facts.append(
            f"memory with {connections} connections open at once (opened in {memory.get('opening_ms')} ms): the Go runtime holds {mib(held)} "
            f"(heap {mib(number(memory.get('go_heap_kib')))}, stacks {mib(number(memory.get('go_stacks_kib')))}; {mib(number(memory.get('go_held_idle_kib')))} "
            f"idle connected), {memory.get('goroutines')} goroutines ({memory.get('goroutines_idle')} idle); the whole process (the app's and the tests' too) "
            f"{mib(peak)} at its peak, {mib(idle)} idle connected, {mib(number(memory.get('rss_disconnected_kib')))} before connecting"
            + (f", so {mib(peak - idle)} more for the connections" if peak is not None and idle is not None else "")
        )
    met = None if held is None or connections < CONNECTIONS else held < ENGINE_KIB_AT_200
    bars.append(bar("Engine memory", f"< {ENGINE_KIB_AT_200 // 1024} MB at {CONNECTIONS} concurrent connections",
                    "?" if held is None else f"{mib(held)} held by the Go runtime at {connections} connections", met))

    # Idle while connected, screen off.
    idle = find(found, "idle")
    if idle:
        facts.append(
            f"idle while connected, screen off, {idle.get('seconds')} s: CPU {idle.get('cpu_ms')} ms ({idle.get('cpu_pct')} % of one core, "
            f"{idle.get('cpu_s_per_hour')} s an hour); {idle.get('bytes')} bytes through the VPN meanwhile (other apps'); "
            f"goroutines {idle.get('goroutines_before')} before, {idle.get('goroutines_after')} after"
        )
    bars.append(bar("Battery when idle-connected", "< 2 % per hour above baseline (screen off, no traffic)",
                    "a phone's battery only; the emulator gives the CPU while idle" + (f": {idle.get('cpu_pct')} % of one core" if idle else ""), None))
    return facts, bars, problems


def vpn_bytes(found: list[dict[str, str]]) -> str:
    m = find(found, "throughput")
    size = number(m.get("bytes")) if m else None
    return "?" if size is None else f"{size / 1024 / 1024:.0f} MiB each"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--pf", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--host-pcap")
    args = parser.parse_args(argv[1:])

    dumped = read(os.path.join(args.out, "logcat.txt"))
    streamed = read(os.path.join(args.out, "logcat-live.txt"))
    logcat = dumped if len(dumped) >= len(streamed) else streamed
    found = measurements(logcat)
    facts, bars, problems = report(found)
    lines = [f"API {args.api}"] + facts + [f"bar: {b['bar']}: {b['value']} — {b['target']}: {verdict(b['met'])}" for b in bars]

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "m10-performance.json"), "w", encoding="utf-8") as f:
        json.dump({"api": args.api, "measurements": found, "bars": bars, "problems": problems}, f, indent=2)
    with open(os.path.join(args.out, "m10-performance.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines + [f"PROBLEM: {p}" for p in problems]) + "\n")
    annotate("notice", f"M10 performance (API {args.api})", lines)
    past = [f"{b['bar']}: {b['value']} — {b['target']}" for b in bars if b["met"] is False]
    if past:
        annotate("warning", f"M10 performance past a bar (API {args.api}, an emulator)", past)
    if problems:
        annotate("error", f"M10 performance (API {args.api})", problems)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
