#!/usr/bin/env python3
"""Checks the M7 end-to-end evidence after the device tests (CI device job), beside m5_evidence.py and m6_evidence.py.

The M7 device tests (app/src/androidTest/.../e2e: ReconnectEndToEndTest, LogsEndToEndTest) log under the tag
ProxyFlowE2E what they measured, and the session logs its reconnections under ProxyFlowVpn. For one API level, and
only what the device tests caused (scripts/e2e_env.sh recorded each lab log's length after its own checks), this reads:

  - the traffic counters against the controlled transfers: on each "traffic <protocol> up expected=E counted=C down
    expected=E counted=C" line, E <= C <= E + 2 % + 256 KiB both ways (ADR-0012); the bulk server's log must show every
    upload read in full and every download sent in full, and each of its connections must have come through a proxy
    (Dante's and Squid's tunnels to the bulk port);
  - the network change: the connection held open ended early, as the engine's reset ended it ("network change: held
    connection ENDED <ms> <how>"), and the bulk server saw it end;
  - the session's reconnections: no attempt beyond its policy's limit ("reconnect attempt n of N", n <= N), each
    reconnection ended (connected again, stopped after its last attempt, blocked, offline or disconnected) before
    logcat ends, and how they ended;
  - the log checks the tests made ("log checked ...", "log cleared ...").

It fails on any of these that does not hold, or when the M7 tests did not run.

Usage: m7_evidence.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m5_evidence import annotate  # noqa: E402
from m6_evidence import baseline, read  # noqa: E402

BULK_PORT = 8090
TOLERANCE_BYTES = 256 * 1024
HOLD_SECONDS = 60

TRAFFIC = re.compile(r"ProxyFlowE2E\s*:\s*traffic (\S+) up expected=(\d+) counted=(\d+) down expected=(\d+) counted=(\d+)")
HELD = re.compile(r"ProxyFlowE2E\s*:\s*network change: held connection ENDED (\d+) (\w+)")
LOG_CHECKS = re.compile(r"ProxyFlowE2E\s*:\s*(log checked .*|log cleared .*)$")
VPN = re.compile(r"ProxyFlowVpn\s*:\s*(.*)$")


def allowed(expected: int) -> int:
    """The most the counters may read for [expected] bytes: other apps' traffic through the VPN meanwhile counts too."""
    return expected + expected // 50 + TOLERANCE_BYTES


def transfers(logcat: list[str]) -> list[tuple[str, int, int, int, int]]:
    return [(m.group(1), *map(int, m.groups()[1:])) for line in logcat if (m := TRAFFIC.search(line))]


def check_transfers(found: list[tuple[str, int, int, int, int]]) -> tuple[list[str], list[str]]:
    facts, problems = [], []
    for protocol, up_expected, up_counted, down_expected, down_counted in found:
        for direction, expected, counted in (("up", up_expected, up_counted), ("down", down_expected, down_counted)):
            if not expected <= counted <= allowed(expected):
                problems.append(f"traffic {protocol} {direction}: counted {counted} bytes for {expected}, outside {expected}..{allowed(expected)}")
        facts.append(
            f"traffic {protocol}: up {up_counted} counted for {up_expected} sent (+{up_counted - up_expected}), "
            f"down {down_counted} for {down_expected} (+{down_counted - down_expected})"
        )
    return facts, problems


def bulk_requests(lines: list[str]) -> dict[str, list[tuple[int, int] | tuple[int, float, str] | str]]:
    """The bulk server's log: uploads (asked, read), downloads (asked, sent), holds (seconds, lasted, how), the rest."""
    found: dict[str, list] = {"up": [], "down": [], "hold": [], "other": []}
    for line in lines:
        if m := re.search(r" up (\d+) got=(\d+)$", line):
            found["up"].append((int(m.group(1)), int(m.group(2))))
        elif m := re.search(r" down (\d+) sent=(\d+)$", line):
            found["down"].append((int(m.group(1)), int(m.group(2))))
        elif m := re.search(r" hold (\d+) ended after ([\d.]+) s \((\w+)\)$", line):
            found["hold"].append((int(m.group(1)), float(m.group(2)), m.group(3)))
        elif line.strip():
            found["other"].append(line.strip())
    return found


def check_bulk(requests: dict, squid: list[str], dante: list[str], expected_transfers: int) -> tuple[list[str], list[str]]:
    facts, problems = [], []
    short_up = [(n, got) for n, got in requests["up"] if got != n]
    short_down = [(n, sent) for n, sent in requests["down"] if sent != n]
    if short_up:
        problems.append(f"bulk server: uploads not read in full (asked, read): {short_up[:3]}")
    if short_down:
        problems.append(f"bulk server: downloads not sent in full (asked, sent): {short_down[:3]}")
    if requests["other"]:
        problems.append(f"bulk server: unexpected requests: {requests['other'][:3]}")
    if len(requests["up"]) < expected_transfers or len(requests["down"]) < expected_transfers:
        problems.append(f"bulk server: {len(requests['up'])} uploads and {len(requests['down'])} downloads for {expected_transfers} counted transfers")
    connections = sum(len(requests[kind]) for kind in ("up", "down", "hold")) + len(requests["other"])
    squid_tunnels = [line for line in squid if "TCP_TUNNEL/200" in line and f":{BULK_PORT} " in line]
    dante_tunnels = [line for line in dante if re.search(rf"tcp/connect \[: .*(10\.99\.0\.1|proxyflow\.test)\.{BULK_PORT}\b", line) and "block(" not in line]
    tunnels = len(squid_tunnels) + len(dante_tunnels)
    facts.append(
        f"bulk server: {len(requests['up'])} uploads and {len(requests['down'])} downloads, each in full; {len(requests['hold'])} held; "
        f"{connections} connections, {len(squid_tunnels)} Squid tunnels + {len(dante_tunnels)} Dante tunnels to it"
    )
    if connections != tunnels:
        problems.append(f"bulk server: {connections} connections but the proxies opened {tunnels} tunnels to it: something arrived without a proxy")
    return facts, problems


def check_hold(logcat: list[str], requests: dict) -> tuple[list[str], list[str]]:
    facts, problems = [], []
    held = [(int(m.group(1)), m.group(2)) for line in logcat if (m := HELD.search(line))]
    if not held:
        problems.append("no held connection in logcat: the network-change test did not run")
    for ms, how in held:
        if ms >= HOLD_SECONDS * 1000 // 2 or how not in ("reset", "closed"):
            problems.append(f"the held connection lasted {ms} ms and ended {how}: the network change did not end it")
    early = [h for h in requests["hold"] if h[1] < h[0] / 2]
    if held and not early:
        problems.append(f"the bulk server saw no held connection end early: {requests['hold'][:3]}")
    if held:
        facts.append(
            "network change: the connection held open ended after "
            + ", ".join(f"{ms} ms ({how})" for ms, how in held)
            + f"; the bulk server saw {len(early)} held connection(s) end early"
        )
    return facts, problems


def reconnections(logcat: list[str]) -> tuple[list[str], list[str]]:
    """Follows the session's reconnections in its log: their attempts stay within the limit, and each one ends."""
    facts, problems = [], []
    running, attempts = None, 0
    outcomes = {"connected again": 0, "stopped after its last attempt": 0, "blocked": 0, "offline": 0, "disconnected": 0, "replaced by a new connection": 0}
    over_limit = []
    for line in logcat:
        m = VPN.search(line)
        if not m:
            continue
        event = m.group(1).strip()
        if event.startswith("reconnecting: "):
            running = event
            continue
        if attempt := re.match(r"reconnect attempt (\d+) of (\d+)$", event):
            attempts += 1
            if int(attempt.group(1)) > int(attempt.group(2)):
                over_limit.append(event)
            continue
        if running is None:
            continue
        ended = (
            "connected again" if event.startswith("reconnected ")
            else "stopped after its last attempt" if event.startswith("reconnecting stopped after ")
            else "blocked" if event.startswith("blocked: ")
            else "offline" if event == "offline"
            else "disconnected" if event.startswith(("disconnect", "revoked", "service destroyed"))
            else "replaced by a new connection" if event.startswith("connect ")
            else None
        )
        if ended:
            outcomes[ended] += 1
            running = None
    if over_limit:
        problems.append(f"reconnection attempts beyond their limit: {over_limit[:3]}")
    if running is not None:
        problems.append(f"a reconnection never ended before logcat did: {running!r}")
    started = sum(outcomes.values()) + (1 if running else 0)
    if started == 0:
        problems.append("no reconnection in logcat: the M7 reconnection tests did not run")
    facts.append(
        f"reconnections: {started}, with {attempts} attempts, none beyond its limit; ended: "
        + ", ".join(f"{name} {count}" for name, count in outcomes.items() if count)
    )
    return facts, problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--pf", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--host-pcap")
    args = parser.parse_args(argv[1:])
    facts: list[str] = [f"API {args.api}"]
    problems: list[str] = []
    base = baseline(args.pf)
    if not base:
        problems.append("no log-baseline.properties: the lab did not finish its own checks")

    def after(name: str) -> list[str]:
        return read(os.path.join(args.pf, name))[base.get(name, 0):]

    dumped = read(os.path.join(args.out, "logcat.txt"))
    streamed = read(os.path.join(args.out, "logcat-live.txt"))
    logcat = dumped if len(dumped) >= len(streamed) else streamed

    found = transfers(logcat)
    if not found:
        problems.append("no counted transfer in logcat: the traffic counter test did not run")
    for more_facts, more_problems in (
        check_transfers(found),
        check_bulk(bulk_requests(after("bulk.log")), after("squid-access.log"), after("danted.log"), len(found)),
        check_hold(logcat, bulk_requests(after("bulk.log"))),
        reconnections(logcat),
    ):
        facts += more_facts
        problems += more_problems
    checks = [m.group(1) for line in logcat if (m := LOG_CHECKS.search(line))]
    facts.append(f"diagnostic log: {len(checks)} checks: " + "; ".join(checks[:4]))
    if not checks:
        problems.append("no log check in logcat: the diagnostic log tests did not run")

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "m7-evidence.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(facts + [f"PROBLEM: {p}" for p in problems]) + "\n")
    annotate("notice", f"M7 evidence (API {args.api})", facts)
    if problems:
        annotate("error", f"M7 evidence (API {args.api})", problems)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
