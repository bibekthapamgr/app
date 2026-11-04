#!/usr/bin/env python3
"""Reports M10's performance baseline for one API level after the device tests (CI device job), beside the M5–M8 evidence.

PerformanceEndToEndTest (app/src/androidTest/.../e2e) logs each measurement as one logcat line under the tag ProxyFlowPerf,
"PF-PERF <name> key=value ...", before it asserts anything about it. This reads them from the device tests' logcat, checks
that each line's figures agree with each other (a rate with its bytes and time, CPU per gigabyte with its milliseconds and
the engine's bytes: the units end to end), sets them against the V1 quality bars (docs/m1/01-scope.md §7) and writes them
as two notices — the summary row and the bars first, the figures after — and as m10-performance.json, from which
docs/m10/performance-baseline.md is made.

The bars are a phone's. An emulator on a CI runner shares the runner's CPU with the lab's proxies and has its own network,
so a figure past its bar is reported (a warning), not failed. What fails: a measurement that is missing (the test did not
run, or stopped part way), figures that disagree with each other, and a connection that opened while the proxy refused
every one — it would have gone around it.

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
TRAFFIC = ("idle", "in_flight")

# What PerformanceEndToEndTest logs when every test ran to the end: (measurement, the fields that tell its lines apart).
EXPECTED = (
    [("connect", {"protocol": p}) for p in PROTOCOLS]
    + [("setup", {"protocol": p, "path": path}) for p in PROTOCOLS for path in ("direct_to_proxy", "vpn")]
    + [("fail_closed", {"proxy": proxy}) for proxy in ("refusing", "silent")]
    + [("throughput", {"protocol": p, "direction": d, "path": path}) for p in PROTOCOLS for d in ("down", "up") for path in ("proxy_only", "vpn")]
    + [("cpu_at_50mbit", {"protocol": p}) for p in PROTOCOLS]
    + [("memory", {"traffic": t}) for t in TRAFFIC]
    + [("idle", {})]
)

# The V1 quality bars (docs/m1/01-scope.md §7), as the emulator's figures are set against them.
CONNECT_P90_MS = 3000
FAIL_CLOSED_MS = 1000
DOWN_MBIT_S = 100.0
CPU_PCT_AT_50_MBIT = 50.0
ENGINE_BYTES_AT_200 = 50_000_000  # "50 MB": megabytes of 10^6 bytes
CONNECTIONS = 200
GB_PER_SECOND_AT_50_MBIT = 0.00625  # 50 Mbit/s is 6.25 MB, so 0.00625 GB, a second


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
    """A KiB figure in MiB and in MB (10^6 bytes, the bar's unit)."""
    return "?" if kib is None else f"{kib / 1024:.1f} MiB ({kib * 1024 / 1e6:.1f} MB)"


def bar(name: str, target: str, value: str, met: bool | None) -> dict[str, object]:
    return {"bar": name, "target": target, "value": value, "met": met}


def verdict(met: bool | None) -> str:
    return {True: "within", False: "PAST", None: "not measured"}[met]


def agrees(logged: float | None, computed: float | None, decimals: int) -> bool:
    """A figure logged with [decimals] places is the one its own inputs give (half a unit of its last place either way)."""
    if logged is None or computed is None:
        return True
    return abs(logged - computed) <= 0.5 * 10 ** -decimals + 1e-9


def inconsistencies(found: list[dict[str, str]]) -> list[str]:
    """Figures a line logged that its own inputs don't give: a unit, a size or a formula got wrong on the way."""
    problems = []
    for m in found:
        label = " ".join([m["name"]] + [f"{k}={m[k]}" for k in ("protocol", "direction", "path", "traffic") if k in m])
        if m["name"] == "throughput":
            rate = None
            if number(m.get("timed_bytes")) is not None and number(m.get("ms")):
                rate = number(m["timed_bytes"]) * 8 / 1000 / max(number(m["ms"]), 1)
            if not agrees(number(m.get("mbit_s")), rate, 1):
                problems.append(f"{label}: {m.get('mbit_s')} Mbit/s, but {m.get('timed_bytes')} bytes in {m.get('ms')} ms are {rate:.1f}")
            per_gb = None
            if number(m.get("cpu_ms")) is not None and number(m.get("engine_bytes")):
                per_gb = number(m["cpu_ms"]) / 1000 / (number(m["engine_bytes"]) / 1e9)
                if not agrees(number(m.get("cpu_s_per_gb")), per_gb, 2):
                    problems.append(f"{label}: {m.get('cpu_s_per_gb')} CPU s per GB, but {m.get('cpu_ms')} ms over {m.get('engine_bytes')} bytes are {per_gb:.2f}")
            if number(m.get("cpu_s_per_gb")) is not None and not agrees(number(m.get("cpu_pct_at_50mbit")), number(m["cpu_s_per_gb"]) * GB_PER_SECOND_AT_50_MBIT * 100, 1):
                problems.append(f"{label}: {m.get('cpu_pct_at_50mbit')} % at 50 Mbit/s, but {m.get('cpu_s_per_gb')} s per GB give "
                                f"{number(m['cpu_s_per_gb']) * GB_PER_SECOND_AT_50_MBIT * 100:.1f}")
        elif m["name"] == "cpu_at_50mbit" and number(m.get("ms")):
            ms = max(number(m["ms"]), 1)
            if number(m.get("bytes")) is not None and not agrees(number(m.get("mbit_s")), number(m["bytes"]) * 8 / 1000 / ms, 1):
                problems.append(f"{label}: {m.get('mbit_s')} Mbit/s, but {m.get('bytes')} bytes in {m.get('ms')} ms are {number(m['bytes']) * 8 / 1000 / ms:.1f}")
            if number(m.get("cpu_ms")) is not None and not agrees(number(m.get("cpu_pct")), number(m["cpu_ms"]) * 100 / ms, 1):
                problems.append(f"{label}: {m.get('cpu_pct')} % of a core, but {m.get('cpu_ms')} ms of CPU in {m.get('ms')} ms are {number(m['cpu_ms']) * 100 / ms:.1f}")
    return problems


def report(found: list[dict[str, str]]) -> tuple[list[str], list[dict[str, object]], list[str]]:
    """The facts for the notice, the bars with each figure set against them, and the problems that fail the step."""
    facts: list[str] = []
    bars: list[dict[str, object]] = []
    problems: list[str] = []
    for name, keys in EXPECTED:
        if find(found, name, **keys) is None:
            label = " ".join([name] + [f"{k}={v}" for k, v in keys.items()])
            problems.append(f"no \"{label}\" measurement in logcat: PerformanceEndToEndTest did not run, or stopped before it")
    problems += [f"figures that disagree (units?): {p}" for p in inconsistencies(found)]

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

    # A new connection's set-up through the proxy, beside a TCP connection straight to it.
    parts = []
    for protocol in PROTOCOLS:
        m, direct = find(found, "setup", protocol=protocol, path="vpn"), find(found, "setup", protocol=protocol, path="direct_to_proxy")
        if m:
            parts.append(
                f"{NAMES[protocol]} p50 {m.get('p50_ms')} ms, p90 {m.get('p90_ms')} ms, max {m.get('max_ms')} ms (n={m.get('n')})"
                + (f", a TCP connection straight to the proxy p50 {direct.get('p50_ms')} ms, p90 {direct.get('p90_ms')} ms" if direct else "")
            )
    if parts:
        facts.append("a new connection's set-up through ProxyFlow and the proxy (the app's SYN to its SYN-ACK): " + "; ".join(parts))

    # Fail closed: the bar is the connections' while the session was still connected (then it reconnects, and holds them).
    refusing, silent = find(found, "fail_closed", proxy="refusing"), find(found, "fail_closed", proxy="silent")
    first = number(refusing.get("first_ms")) if refusing else None
    worst_connected = number(refusing.get("worst_connected_ms")) if refusing else None
    if refusing:
        opened = int(number(refusing.get("opened")) or 0)
        facts.append(
            f"fail closed, the proxy refusing: the first new connection failed after {refusing.get('first_ms')} ms, the worst of the "
            f"{refusing.get('connected_probes', '?')} made while the session was still connected after {refusing.get('worst_connected_ms')} ms; "
            f"{opened} of {len(refusing.get('results', '').split(','))} opened; each (result:ms:exception:the session's state): "
            f"{refusing.get('results')}; the session then: {refusing.get('status')}"
        )
        if opened:
            problems.append(f"{opened} connection(s) opened while the proxy refused every connection: they went around it ({refusing.get('results')})")
    if silent:
        facts.append(f"fail closed, the proxy accepting and never answering: the connection failed after {silent.get('first_ms')} ms (the handshake timeout); "
                     f"the session then: {silent.get('status')}")
        if int(number(silent.get("opened")) or 0):
            problems.append(f"a connection opened through a proxy that never answers: it went around it ({silent.get('results')})")
    judged = worst_connected if worst_connected is not None else first
    bars.append(bar("Fail-closed reaction when the proxy dies",
                    f"new connections refused in < {FAIL_CLOSED_MS} ms while the session is connected (no direct packet: the M6 evidence's captures)",
                    "?" if judged is None else f"{judged:.0f} ms (the worst while connected; the first {refusing.get('first_ms') if refusing else '?'} ms)",
                    None if judged is None else judged < FAIL_CLOSED_MS))

    # Throughput, and the CPU it costs.
    down, extrapolated_down = [], []
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
                f"{NAMES[protocol]} {vpn.get('mbit_s')} Mbit/s through ProxyFlow (each {vpn.get('each_mbit_s')}; set-up {vpn.get('setup_ms')} ms), "
                f"{only.get('mbit_s') if only else '?'} straight through the proxy (each {only.get('each_mbit_s') if only else '?'}; "
                f"set-up {only.get('setup_ms') if only else '?'} ms){share}; CPU {vpn.get('cpu_s_per_gb')} s per GB the engine carried, "
                f"{vpn.get('cpu_pct_at_50mbit')} % of one core at 50 Mbit/s by that rate"
            )
            if direction == "down" and rate is not None:
                down.append(rate)
            if direction == "down" and number(vpn.get("cpu_pct_at_50mbit")) is not None:
                extrapolated_down.append(number(vpn.get("cpu_pct_at_50mbit")))
        if parts:
            facts.append(f"throughput {direction} ({vpn_bytes(found)}, timed without its set-up): " + "; ".join(parts))
    slowest = min(down, default=None)
    bars.append(bar("Throughput down through a local test proxy", f"≥ {DOWN_MBIT_S:.0f} Mbit/s", "?" if slowest is None else f"{slowest:.1f} Mbit/s (the slower protocol)",
                    None if slowest is None else slowest >= DOWN_MBIT_S))
    measured = []
    for protocol in PROTOCOLS:
        paced = find(found, "cpu_at_50mbit", protocol=protocol)
        if paced and number(paced.get("cpu_pct")) is not None:
            measured.append(number(paced["cpu_pct"]))
            facts.append(f"CPU at 50 Mbit/s, measured, {NAMES[protocol]}: an upload paced at it, {paced.get('bytes')} bytes in {paced.get('ms')} ms "
                         f"({paced.get('mbit_s')} Mbit/s): {paced.get('cpu_ms')} ms of CPU, {paced.get('cpu_pct')} % of one core")
    costliest = max(measured + extrapolated_down, default=None)
    bars.append(bar("CPU at 50 Mbit/s", f"< {CPU_PCT_AT_50_MBIT:.0f} % of one core",
                    "?" if costliest is None else f"{costliest:.1f} % (the costliest: uploads paced at 50 Mbit/s "
                    f"{'/'.join(f'{v:.1f}' for v in measured) or '?'} %, downloads by their rate {'/'.join(f'{v:.1f}' for v in extrapolated_down) or '?'} %)",
                    None if costliest is None else costliest < CPU_PCT_AT_50_MBIT))

    # Memory with 200 connections open: idle, then with data in flight on each (the bar's case: buffers in use).
    held_by = {}
    for traffic in TRAFFIC:
        memory = find(found, "memory", traffic=traffic)
        if not memory:
            continue
        held = number(memory.get("go_held_kib"))
        connections = int(number(memory.get("connections")) or 0)
        held_by[traffic] = (held, connections)
        peak, idle, before = number(memory.get("rss_peak_kib")), number(memory.get("rss_idle_kib")), number(memory.get("rss_disconnected_kib"))
        what = "idle" if traffic == "idle" else f"each with {memory.get('load_bytes')} bytes in flight"
        facts.append(
            f"memory with {connections} connections open at once, {what} (opened in {memory.get('opening_ms')} ms): the Go runtime holds {mib(held)} "
            f"(heap {mib(number(memory.get('go_heap_kib')))}, stacks {mib(number(memory.get('go_stacks_kib')))}; {mib(number(memory.get('go_held_idle_kib')))} "
            f"idle connected); goroutines {memory.get('goroutines')} ({memory.get('goroutines_idle')} idle, {memory.get('goroutines_closed')} once all closed); "
            f"the whole process (the app's and the tests' too) {mib(peak)} at its peak, {mib(idle)} idle connected, {mib(before)} before connecting"
            + (f", so {mib(peak - before)} more than before connecting" if peak is not None and before is not None else "")
        )
    held, connections = held_by.get("in_flight", held_by.get("idle", (None, 0)))
    which = "in flight" if "in_flight" in held_by else "idle"
    met = None if held is None or connections < CONNECTIONS else held * 1024 < ENGINE_BYTES_AT_200
    bars.append(bar("Engine memory", f"< {ENGINE_BYTES_AT_200 // 1_000_000} MB (10^6 bytes) at {CONNECTIONS} concurrent connections",
                    "?" if held is None else f"{mib(held)} held by the Go runtime at {connections} connections, data {which}", met))

    # Idle while connected, screen off.
    idle = find(found, "idle")
    if idle:
        facts.append(
            f"idle while connected, the screen off ({idle.get('screen_off')}), {idle.get('seconds')} s: CPU {idle.get('cpu_ms')} ms ({idle.get('cpu_pct')} % of one core, "
            f"{idle.get('cpu_s_per_hour')} s an hour); {idle.get('bytes')} bytes through the VPN meanwhile (other apps'); "
            f"goroutines {idle.get('goroutines_before')} before, {idle.get('goroutines_after')} after (flows {idle.get('flows_before')} → {idle.get('flows_after')})"
        )
    bars.append(bar("Battery when idle-connected", "< 2 % per hour above baseline (screen off, no traffic)",
                    "a phone's battery only; the emulator gives the CPU while idle" + (f": {idle.get('cpu_pct')} % of one core" if idle else ""), None))
    return facts, bars, problems


def vpn_bytes(found: list[dict[str, str]]) -> str:
    """How much each transfer carried, and how many of each kind the median is of."""
    m = find(found, "throughput")
    size = number(m.get("bytes")) if m else None
    count = m.get("n") if m else None
    return "?" if size is None else f"{size / 1024 / 1024:.0f} MiB each, the median of {count or '?'}"


def row(found: list[dict[str, str]]) -> str:
    """The figures docs/m10/performance-baseline.md tabulates, as one "key=value" line ("?" for one not measured)."""
    def get(name: str, field: str, **keys: str) -> str:
        m = find(found, name, **keys)
        return m.get(field, "?") if m else "?"

    values = {}
    for p in PROTOCOLS:
        values[f"connect_p50_{p}"] = get("connect", "p50_ms", protocol=p)
        values[f"connect_p90_{p}"] = get("connect", "p90_ms", protocol=p)
        values[f"setup_p50_{p}"] = get("setup", "p50_ms", protocol=p, path="vpn")
        values[f"direct_p50_{p}"] = get("setup", "p50_ms", protocol=p, path="direct_to_proxy")
        for d in ("down", "up"):
            values[f"{d}_{p}"] = get("throughput", "mbit_s", protocol=p, direction=d, path="vpn")
            values[f"{d}_proxy_only_{p}"] = get("throughput", "mbit_s", protocol=p, direction=d, path="proxy_only")
            values[f"cpu50_{d}_{p}"] = get("throughput", "cpu_pct_at_50mbit", protocol=p, direction=d, path="vpn")
        values[f"cpu50_paced_{p}"] = get("cpu_at_50mbit", "cpu_pct", protocol=p)
    values["fail_closed_ms"] = get("fail_closed", "first_ms", proxy="refusing")
    values["fail_closed_worst_ms"] = get("fail_closed", "worst_connected_ms", proxy="refusing")
    values["silent_ms"] = get("fail_closed", "first_ms", proxy="silent")
    values["connections"] = get("memory", "connections", traffic="idle")
    values["go_held_kib"] = get("memory", "go_held_kib", traffic="idle")
    values["go_held_idle_kib"] = get("memory", "go_held_idle_kib", traffic="idle")
    values["rss_peak_kib"] = get("memory", "rss_peak_kib", traffic="idle")
    values["rss_idle_kib"] = get("memory", "rss_idle_kib", traffic="idle")
    values["loaded_connections"] = get("memory", "connections", traffic="in_flight")
    values["loaded_go_held_kib"] = get("memory", "go_held_kib", traffic="in_flight")
    values["loaded_rss_peak_kib"] = get("memory", "rss_peak_kib", traffic="in_flight")
    values["rss_disconnected_kib"] = get("memory", "rss_disconnected_kib", traffic="idle")
    values["idle_cpu_pct"] = get("idle", "cpu_pct")
    values["idle_s"] = get("idle", "seconds")
    return "row: " + " ".join(f"{k}={v}" for k, v in values.items())


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
    summary = [f"API {args.api}", row(found)] + [f"bar: {b['bar']}: {b['value']} — {b['target']}: {verdict(b['met'])}" for b in bars]

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "m10-performance.json"), "w", encoding="utf-8") as f:
        json.dump({"api": args.api, "measurements": found, "bars": bars, "problems": problems}, f, indent=2)
    with open(os.path.join(args.out, "m10-performance.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(summary + facts + [f"PROBLEM: {p}" for p in problems]) + "\n")
    # The summary first, short enough for one annotation: GitHub keeps 10 notices a step, and M5–M8 come before these.
    annotate("notice", f"M10 performance (API {args.api})", summary)
    if facts:
        annotate("notice", f"M10 performance, the figures (API {args.api})", facts)
    past = [f"{b['bar']}: {b['value']} — {b['target']}" for b in bars if b["met"] is False]
    if past:
        annotate("warning", f"M10 performance past a bar (API {args.api}, an emulator)", past)
    if problems:
        annotate("error", f"M10 performance (API {args.api})", problems)
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception:  # a report that cannot finish is a failed check, said where CI shows annotations
        import traceback

        from m5_evidence import escape

        api = next((sys.argv[i + 1] for i, a in enumerate(sys.argv[:-1]) if a == "--api"), "?")
        print(f"::error title=M10 performance (API {api})::the report script failed: {escape(traceback.format_exc())}")
        sys.exit(1)
