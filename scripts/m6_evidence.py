#!/usr/bin/env python3
"""Checks the M6 end-to-end evidence after the device tests (CI device job), beside m5_evidence.py.

The M6 device tests (app/src/androidTest/.../e2e: RoutingEndToEndTest, TlsEndToEndTest) log,
under the tag ProxyFlowE2E, every line they send ("sent <line> to <host>:<port>") and every
line that must get nowhere ("refused <line> to <host>:<port>"). This script reads, for one API
level, only what the device tests caused (scripts/e2e_env.sh recorded each log's length after
its own checks, in log-baseline.properties):

  - every destination's log, the HTTPS destination's and the UDP sinks';
  - the proxies' logs (Squid, Dante), the TLS fronts' (stunnel) and the gate's;
  - the two DNS servers' logs: what the proxies resolved, and what the phone looked up on its
    uplink;
  - packet captures of the phone's uplink: the emulator's own (-tcpdump) and tcpdump inside the
    phone where the image allows it.

It fails unless every sent line arrived at its destination exactly once and no refused line
arrived anywhere; every connection a destination saw came through a proxy (the count matches
the proxies' tunnels to it); the proxies resolved the lab's names (proxy-side DNS) while the
phone's uplink DNS server was never asked for one of them; no UDP reached a sink; the HTTPS
destination saw the name the client sent (end to end); every connection to a TLS proxy port, in
the captures and at the gate, opened with a TLS record (no plaintext fallback); and no capture
shows a packet to or from a lab destination's address, a DNS query for a lab name, or a direct
connection to the LAN destination. It also prints what the captures show the phone asked the
proxies for (SOCKS5 targets by name or address, HTTP CONNECT targets), for the packet capture
report (docs/m6/packet-capture-report.md), and writes the same as m6-capture-summary.md.

Usage: m6_evidence.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import collections
import glob
import os
import re
import struct
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m5_evidence import dns_name, escape, ip_of, network_payload  # noqa: E402

LAB_NAMES = ("proxyflow.test", "secure.proxyflow.test", "proxyflow6.test", "missing.proxyflow.test")
PROXY_NAME = "proxy.proxyflow.test"
LAB_ADDRESSES = ("10.99.0.1", "10.99.0.2", "fd99::1")  # reachable from the phone only through a proxy
GATEWAY = "10.0.2.2"  # the runner as the emulator sees it: the proxies, and the LAN destination
LAN_PORT = 8082
UDP_PORT = 9999
SOCKS_PORTS = (1080, 1090)  # Dante, and the gate in front of it
HTTP_PORT = 3128
TLS_PORTS = (3129, 1443, 3130, 3131, 3132)

# Where each destination logs, and how the proxies name a tunnel to it.
DESTINATIONS = {
    "10.99.0.1:8080": ("destination.log", ["CONNECT proxyflow.test:8080", "CONNECT 10.99.0.1:8080"], ["10.99.0.1.8080", "proxyflow.test.8080"]),
    "proxyflow.test:8080": ("destination.log", None, None),
    "10.99.0.2:8080": ("destination-address.log", ["CONNECT 10.99.0.2:8080"], ["10.99.0.2.8080"]),
    "[fd99::1]:8080": ("destination6.log", ["CONNECT [fd99::1]:8080", "CONNECT fd99::1:8080", "CONNECT proxyflow6.test:8080"], ["fd99::1.8080"]),
    "proxyflow6.test:8080": ("destination6.log", None, None),
    "10.0.2.2:8082": ("destination-lan.log", ["CONNECT 10.0.2.2:8082"], ["10.0.2.2.8082"]),
    "secure.proxyflow.test:8443": ("tls-destination.log", ["CONNECT secure.proxyflow.test:8443"], ["10.99.0.1.8443"]),
}
M6_LOGS = ("destination-address.log", "destination6.log", "destination-lan.log", "tls-destination.log")


def read(path: str) -> list[str]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return []


def baseline(pf: str) -> dict[str, int]:
    values = {}
    for line in read(os.path.join(pf, "log-baseline.properties")):
        if "=" in line:
            name, count = line.split("=", 1)
            values[name.strip()] = int(count.strip() or 0)
    return values


# --- packet captures --------------------------------------------------------


@dataclass
class Capture:
    path: str
    packets: int = 0
    error: str | None = None
    syns: collections.Counter = field(default_factory=collections.Counter)  # (destination, port)
    lab_packets: collections.Counter = field(default_factory=collections.Counter)  # lab address -> packets
    lan_syns: int = 0
    udp_to_gateway_sink: int = 0
    dns_names: collections.Counter = field(default_factory=collections.Counter)
    socks_targets: collections.Counter = field(default_factory=collections.Counter)  # "name x" / "IPv4 x" / "IPv6 x"
    connect_targets: collections.Counter = field(default_factory=collections.Counter)
    tls_port_streams: collections.Counter = field(default_factory=collections.Counter)  # (port, "TLS"|"plain")
    plaintext_on_tls: list[str] = field(default_factory=list)
    first_payload_seen: set = field(default_factory=set)
    segments_seen: set = field(default_factory=set)

    @property
    def covers_uplink(self) -> bool:
        to_proxies = [port for (destination, port) in self.syns if destination == GATEWAY]
        return any(port in SOCKS_PORTS for port in to_proxies) and HTTP_PORT in to_proxies


def socks_connect_target(payload: bytes) -> str | None:
    """The target of a SOCKS5 CONNECT request (never the greeting or the sign-in, which holds the password)."""
    if len(payload) < 10 or payload[:3] != b"\x05\x01\x00":
        return None
    kind = payload[3]
    if kind == 1 and len(payload) >= 10:
        return f"IPv4 {ip_of(payload[4:8])}:{struct.unpack('!H', payload[8:10])[0]}"
    if kind == 4 and len(payload) >= 22:
        return f"IPv6 [{ip_of(payload[4:20])}]:{struct.unpack('!H', payload[20:22])[0]}"
    if kind == 3 and len(payload) >= 5 + payload[4] + 2:
        name = payload[5 : 5 + payload[4]].decode("ascii", "replace")
        return f"name {name}:{struct.unpack('!H', payload[5 + payload[4] : 7 + payload[4]])[0]}"
    return None


def inspect(packet: bytes, capture: Capture) -> None:
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        header = (packet[0] & 0x0F) * 4
        proto, src, dst, rest = packet[9], ip_of(packet[12:16]), ip_of(packet[16:20]), packet[header:]
    elif version == 6 and len(packet) >= 40:
        proto, src, dst, rest = packet[6], ip_of(packet[8:24]), ip_of(packet[24:40]), packet[40:]
    else:
        return
    for address in LAB_ADDRESSES:
        if address in (src, dst):
            capture.lab_packets[address] += 1
    if proto == 6 and len(rest) >= 20:
        sport, dport, seq = struct.unpack("!HHI", rest[0:8])
        flags = rest[13]
        offset = (rest[12] >> 4) * 4
        payload = rest[offset:]
        if flags & 0x02 and not flags & 0x10:
            capture.syns[(dst, dport)] += 1
            if dst == GATEWAY and dport == LAN_PORT:
                capture.lan_syns += 1
        if 53 in (sport, dport) and len(payload) > 2:
            name = dns_name(payload[2:])
            if name is not None and dport == 53:
                capture.dns_names[name.rstrip(".")] += 1
        if not payload or dst != GATEWAY:
            return
        flow = (src, sport, dst, dport)
        segment = (flow, seq)
        if segment in capture.segments_seen:
            return  # a retransmission
        capture.segments_seen.add(segment)
        first = flow not in capture.first_payload_seen
        capture.first_payload_seen.add(flow)
        if dport in SOCKS_PORTS:
            target = socks_connect_target(payload)
            if target:
                capture.socks_targets[target] += 1
        elif dport == HTTP_PORT and payload.startswith(b"CONNECT "):
            capture.connect_targets[payload.split(b" ", 2)[1].decode("ascii", "replace")] += 1
        elif dport in TLS_PORTS and first:
            tls = payload[0] == 0x16 and payload[1:2] == b"\x03"
            capture.tls_port_streams[(dport, "TLS" if tls else "plain")] += 1
            if not tls:
                capture.plaintext_on_tls.append(f"{dport}: {payload[:8].hex(' ')}")
    elif proto == 17 and len(rest) >= 8:
        sport, dport = struct.unpack("!HH", rest[0:4])
        if dport == 53:
            name = dns_name(rest[8:])
            if name is not None:
                capture.dns_names[name.rstrip(".")] += 1
        if dst == GATEWAY and dport == UDP_PORT:
            capture.udp_to_gateway_sink += 1


def read_capture(path: str) -> Capture:
    capture = Capture(path)
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        capture.error = str(e)
        return capture
    if len(data) < 24:
        capture.error = f"{len(data)} bytes: no capture"
        return capture
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
        endian = "<"
    elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        endian = ">"
    else:
        capture.error = f"not a pcap file (magic {magic.hex()})"
        return capture
    linktype = struct.unpack(endian + "I", data[20:24])[0] & 0x0FFFFFFF
    at = 24
    while at + 16 <= len(data):
        incl_len = struct.unpack(endian + "I", data[at + 8 : at + 12])[0]
        frame = data[at + 16 : at + 16 + incl_len]
        at += 16 + incl_len
        capture.packets += 1
        try:
            packet = network_payload(linktype, frame)
            if packet:
                inspect(packet, capture)
        except (IndexError, struct.error):
            continue
    return capture


# --- the checks -----------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--pf", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--host-pcap")
    args = parser.parse_args(argv[1:])
    problems: list[str] = []
    facts: list[str] = [f"API {args.api}"]
    summary: list[str] = [f"### API {args.api}", ""]
    base = baseline(args.pf)
    if not base:
        problems.append("no log-baseline.properties: the lab did not finish its own checks")

    def after(name: str) -> list[str]:
        return read(os.path.join(args.pf, name))[base.get(name, 0) :]

    dumped = read(os.path.join(args.out, "logcat.txt"))
    streamed = read(os.path.join(args.out, "logcat-live.txt"))
    logcat = dumped if len(dumped) >= len(streamed) else streamed
    sent = [m.groups() for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:\s*sent (\S+) to (\S+)", line))]
    refused = [m.groups() for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:\s*refused (\S+) to (\S+)", line))]
    facts.append(f"logcat: {len(sent)} lines sent, {len(refused)} lines that had to be refused")
    if not sent:
        problems.append("no sent line in logcat: the M6 tests did not run (or logcat lost their lines)")

    # Lines: each sent line once at its destination, no refused line anywhere.
    logs = {name: after(name) for name in ("destination.log", "sink.log") + M6_LOGS}
    arrived_lines = {name: [line.split(" ", 2)[2] if line.count(" ") >= 2 else "" for line in lines] for name, lines in logs.items()}
    arrived_lines["tls-destination.log"] = [m.group(1) for line in logs["tls-destination.log"] if (m := re.search(r"line=(\S+)", line))]
    for line, endpoint in sent:
        destination = DESTINATIONS.get(endpoint)
        if destination is None:
            problems.append(f"sent line {line} to {endpoint}, which is no lab destination")
            continue
        count = arrived_lines[destination[0]].count(line)
        if count != 1:
            problems.append(f"line {line} sent to {endpoint} arrived {count} times at {destination[0]}, expected once")
    for line, endpoint in refused:
        hits = [name for name, lines in arrived_lines.items() if line in lines]
        if hits:
            problems.append(f"line {line} to {endpoint} had to fail but arrived at {hits}")
    facts.append("lines: every sent line arrived once where it was sent; no refused line arrived anywhere")

    # Every connection a destination saw came through a proxy.
    squid = after("squid-access.log")
    dante = after("danted.log")
    for log_name in M6_LOGS:
        squid_names, dante_names = next((d[1], d[2]) for d in DESTINATIONS.values() if d[0] == log_name and d[1])
        squid_tunnels = [line for line in squid if "TCP_TUNNEL/200" in line and any(f"{n} " in line for n in squid_names)]
        dante_tunnels = [
            line for line in dante if re.search(r"tcp/connect \[: .*(" + "|".join(re.escape(n) for n in dante_names) + r")\b", line) and "block(" not in line
        ]
        connections = len(logs[log_name])
        tunnels = len(squid_tunnels) + len(dante_tunnels)
        facts.append(f"{log_name}: {connections} connections, {len(squid_tunnels)} Squid tunnels + {len(dante_tunnels)} Dante tunnels to it")
        if connections != tunnels:
            problems.append(f"{log_name}: {connections} connections but the proxies opened {tunnels} tunnels to it: something arrived without a proxy")
    tls_destination = logs["tls-destination.log"]
    wrong_sni = [line for line in tls_destination if "sni=secure.proxyflow.test" not in line]
    if wrong_sni:
        problems.append(f"the HTTPS destination saw other names: {wrong_sni[:3]}")
    facts.append(f"HTTPS destination: {len(tls_destination)} TLS sessions, every one for secure.proxyflow.test (the client's own TLS, end to end)")

    udp = after("udp-sink.log")
    facts.append(f"UDP sinks: {len(udp)} datagrams arrived")
    if udp:
        problems.append(f"UDP reached a sink around the proxy: {udp[:3]}")

    # DNS: the proxies resolved the lab's names; the phone's uplink resolver was never asked for one.
    def queries(lines: list[str]) -> collections.Counter:
        found: collections.Counter = collections.Counter()
        for line in lines:
            m = re.search(r"query\[(\w+)\] (\S+) from", line)
            if m:
                found[(m.group(2).lower().rstrip("."), m.group(1))] += 1
        return found

    proxies_dns = queries(after("dns-proxies.log"))
    uplink_dns = queries(after("dns-uplink.log"))
    resolved_by_proxies = sorted({name for (name, _) in proxies_dns if name in LAB_NAMES})
    facts.append(f"the proxies' DNS server: {sum(proxies_dns.values())} queries; lab names resolved by the proxies: {resolved_by_proxies}")
    for name in ("proxyflow.test", "proxyflow6.test", "missing.proxyflow.test"):
        if name not in resolved_by_proxies:
            problems.append(f"the proxies never resolved {name}: the name did not reach a proxy as a name")
    direct_lab = {f"{name} {kind}": count for (name, kind), count in uplink_dns.items() if name in LAB_NAMES}
    proxy_lookups = sum(count for (name, _), count in uplink_dns.items() if name == PROXY_NAME)
    others = collections.Counter({name: count for (name, _), count in uplink_dns.items() if name not in LAB_NAMES and name != PROXY_NAME})
    facts.append(
        f"the phone's uplink DNS server: {sum(uplink_dns.values())} queries; {proxy_lookups} for the proxy's name {PROXY_NAME} (the app's own lookup); "
        f"lab destination names: {direct_lab or 'none'}; other names (Android itself): {dict(others.most_common(6))}"
    )
    if direct_lab:
        problems.append(f"the phone looked up lab destination names itself, around the proxy: {direct_lab}")
    if not uplink_dns:
        facts.append(
            "the phone's uplink DNS server got no query after the lab's checks: this image's uplink does not use it "
            "(the captures still show every DNS query)"
        )

    # The gate: a connection forwarded to a TLS port opens with a TLS record.
    gate_mode, gate_plain, gate_tls = "", [], 0
    for line in after("gate.log"):
        m = re.search(r"mode forward 127\.0\.0\.1:(\d+)", line)
        if m:
            gate_mode = m.group(1)
            continue
        m = re.search(r"connection \d+ from \S+ mode=forward first=(.*)$", line)
        if m and gate_mode and int(gate_mode) in TLS_PORTS:
            if m.group(1).startswith("16 03"):
                gate_tls += 1
            else:
                gate_plain.append(line[-60:])
    if gate_plain:
        facts.append(f"gate: plaintext to a TLS front: {gate_plain[:3]}")
    else:
        facts.append(f"gate: {gate_tls} connections to a TLS front, every one opening with a TLS record")
    if gate_plain:
        problems.append(f"the app sent plaintext to a TLS proxy through the gate: {gate_plain[:3]}")

    stunnel = after("stunnel.log")
    accepted = collections.Counter(m.group(1) for line in stunnel if (m := re.search(r"Service \[([\w-]+)\] accepted connection", line)))
    alerts = collections.Counter(m.group(1) for line in stunnel if (m := re.search(r"SSL_accept: .*alert ([\w ]+)$", line)))
    facts.append(f"TLS fronts: connections {dict(accepted)}; handshakes the app ended with an alert: {dict(alerts)}")

    # Packet captures.
    paths = sorted(glob.glob(os.path.join(args.out, "guest-*.pcap")))
    if args.host_pcap:
        paths.insert(0, args.host_pcap)
    guest = read(os.path.join(args.out, "guest-capture.txt"))
    guest_spans_tests = any(line.startswith("tcpdump at the end: ") and "none" not in line for line in guest)
    covering = 0
    summary += [
        "| Capture | Packets | SOCKS5 targets (by kind) | HTTP CONNECT targets | TLS-port streams | To lab addresses | DNS for lab names |",
        "|---|---|---|---|---|---|---|",
    ]
    for capture in (read_capture(path) for path in paths):
        name = os.path.basename(capture.path)
        if capture.error:
            facts.append(f"capture {name}: unreadable ({capture.error})")
            continue
        kinds = collections.Counter(target.split(" ", 1)[0] for target in capture.socks_targets.elements())
        lab_dns = {n: c for n, c in capture.dns_names.items() if n in LAB_NAMES}
        tls_streams = {f"{port} {kind}": count for (port, kind), count in sorted(capture.tls_port_streams.items())}
        facts.append(
            f"capture {name}: {capture.packets} packets; SOCKS5 CONNECT targets {dict(capture.socks_targets.most_common(8))}; "
            f"HTTP CONNECT targets {dict(capture.connect_targets.most_common(8))}; streams to TLS ports {tls_streams}; "
            f"packets to/from lab addresses {dict(capture.lab_packets) or 0}; SYNs to the LAN destination {capture.lan_syns}; "
            f"UDP to the gateway's sink port {capture.udp_to_gateway_sink}; DNS queries for lab names {lab_dns or 0}; "
            f"DNS names queried {len(capture.dns_names)}"
        )
        summary.append(
            f"| {name} | {capture.packets} | {dict(kinds)} | {sum(capture.connect_targets.values())} | {tls_streams} | "
            f"{sum(capture.lab_packets.values())} | {sum(lab_dns.values())} |"
        )
        partial = name.startswith("guest-") and not guest_spans_tests
        if not partial and capture.covers_uplink:
            covering += 1
        for count_name, count in (
            ("packets to or from a lab destination's address", sum(capture.lab_packets.values())),
            ("SYNs straight to the LAN destination 10.0.2.2:8082", capture.lan_syns),
            ("UDP datagrams to the gateway's UDP sink port", capture.udp_to_gateway_sink),
            ("DNS queries for lab destination names", sum(lab_dns.values())),
        ):
            if count:
                problems.append(f"capture {name}: {count} {count_name}")
        if capture.plaintext_on_tls:
            problems.append(f"capture {name}: plaintext opened a connection to a TLS proxy port: {capture.plaintext_on_tls[:3]}")
        named = [t for t in capture.socks_targets if t.startswith("name proxyflow.test:") or t.startswith("name secure.proxyflow.test:")]
        if capture.covers_uplink and capture.socks_targets and not named:
            problems.append(f"capture {name}: no SOCKS5 request asked for a lab name: the names did not reach the proxy as names")
    if covering == 0:
        problems.append("no packet capture that ran through all the tests saw the app's connections to both proxies")
    summary.append("")

    token = os.urandom(16).hex()
    print(f"::stop-commands::{token}")
    print("\n".join(facts))
    print("\n".join(f"PROBLEM {p}" for p in problems) or "OK")
    print("\n".join(summary))
    print(f"::{token}::")
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "m6-capture-summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(summary + ["", "Facts:", ""] + [f"- {fact}" for fact in facts] + [""]))
    if problems:
        print(f"::error title=M6 end-to-end evidence (API {args.api})::{escape(chr(10).join(problems + facts))}")
        return 1
    print(f"::notice title=M6 end-to-end evidence (API {args.api})::{escape(chr(10).join(facts))}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
