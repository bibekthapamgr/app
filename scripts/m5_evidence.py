#!/usr/bin/env python3
"""Checks the M5 end-to-end evidence after the device tests (CI device job).

The device tests (app/src/androidTest/.../e2e) send lines from the emulator's
shell to proxyflow.test:8080 through the VPN, and log each line under the tag
ProxyFlowE2E ("exchange <line>"; "held <line>" for a line sent while traffic
must be held). This script reads, for one API level:

  - the destination's log: every connection that reached it, with its line;
  - Squid's access log and Dante's log: every tunnel each proxy opened;
  - logcat: the tests' lines, and whether a test password appears anywhere;
  - packet captures of the emulator's uplink: the emulator's own (-tcpdump,
    taken by its network stack outside the phone) and, when the image allows
    root, tcpdump on each uplink interface inside the phone.

It fails unless every exchanged line reached the destination exactly once and
no held line did; every connection that reached the destination came through a
proxy (the count matches the proxies' tunnels to it); Squid was asked for the
destination by name (the phone never resolved it); no capture shows a packet to
or from the destination's address or a DNS query for its name; at least one
capture that ran through all the tests saw the app's connections to both proxies
(so the captures cover the uplink); and no test password is in logcat. Results
are printed and emitted as annotations (readable through the API, like the other
build facts), with the end of the VPN layer's lifecycle log (tag ProxyFlowVpn).

Usage: m5_evidence.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import glob
import ipaddress
import os
import re
import struct
import sys
from dataclasses import dataclass, field

DESTINATION_ADDRESS = "10.99.0.1"
DESTINATION_NAME = "proxyflow.test"
DESTINATION_PORT = 8080
SINK_PORT = 8081  # lines sent while traffic must be held go here, and nothing else does
PROXY_GATEWAY = "10.0.2.2"  # the runner's loopback as the emulator sees it
SOCKS_PORT = 1080
HTTP_PORT = 3128
LIFECYCLE_LINES = 80
LIFECYCLE_CHARS = 3400


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


ANNOTATION_CHARS = 3500  # GitHub cuts an annotation's message at 4096 characters (run 41272331602: M6, API 34)


def annotate(level: str, title: str, lines: list[str]) -> None:
    """Prints [lines] as annotations of at most ANNOTATION_CHARS characters each, "title 1/3", "title 2/3"... when they
    need more than one, so that nothing is cut; a line too long for one is split. GitHub keeps 10 of a kind per step."""
    pieces = [line[i:i + ANNOTATION_CHARS] for line in lines for i in range(0, max(len(line), 1), ANNOTATION_CHARS)]
    chunks: list[str] = []
    for piece in pieces:
        if chunks and len(chunks[-1]) + 1 + len(piece) <= ANNOTATION_CHARS:
            chunks[-1] += "\n" + piece
        else:
            chunks.append(piece)
    for n, chunk in enumerate(chunks):
        suffix = f" {n + 1}/{len(chunks)}" if len(chunks) > 1 else ""
        print(f"::{level} title={title}{suffix}::{escape(chunk)}")


# --- packet captures --------------------------------------------------------


@dataclass
class Capture:
    path: str
    packets: int = 0
    ip_packets: int = 0
    linktype: int | None = None
    error: str | None = None
    syns_to_proxy: dict[int, int] = field(default_factory=lambda: {SOCKS_PORT: 0, HTTP_PORT: 0})
    to_destination: int = 0
    dns_queries: int = 0
    dns_for_destination: list[str] = field(default_factory=list)

    @property
    def covers_uplink(self) -> bool:
        return all(count > 0 for count in self.syns_to_proxy.values())


def ip_of(raw: bytes) -> str:
    """An address in its canonical text form (IPv6 compressed, "fd99::1"), the form the lab's constants use."""
    return str(ipaddress.ip_address(bytes(raw)))


def guest_capture_partial(name: str, any_alive: bool, alive_at_end: list[str] | None, alive_at_start: list[str] | None = None) -> bool:
    """Whether a phone capture (guest-<interface>.pcap) may have stopped before the tests ended: its own tcpdump must
    still run at the end. Without per-interface lines (older runs), or when they could not be read at the start, any
    tcpdump still running counts, as before."""
    if not name.startswith("guest-"):
        return False
    if alive_at_end is None or alive_at_start in (None, ["none"]):
        return not any_alive
    return name[len("guest-") : -len(".pcap")] not in alive_at_end


def dns_name(payload: bytes) -> str | None:
    """The first question's name of a DNS query, or None for a response or something else."""
    if len(payload) < 12 or payload[2] & 0x80:  # QR bit: a response
        return None
    if struct.unpack("!H", payload[4:6])[0] < 1:
        return None
    labels, at = [], 12
    while at < len(payload):
        length = payload[at]
        if length == 0:
            return ".".join(labels).lower()
        if length & 0xC0:
            return None
        labels.append(payload[at + 1 : at + 1 + length].decode("ascii", "replace"))
        at += 1 + length
    return None


def network_payload(linktype: int, frame: bytes) -> bytes | None:
    """The IP packet inside a frame of the capture's link type."""
    if linktype == 1:  # Ethernet
        ethertype, at = struct.unpack("!H", frame[12:14])[0], 14
        while ethertype in (0x8100, 0x88A8) and len(frame) >= at + 4:  # VLAN tags
            ethertype, at = struct.unpack("!H", frame[at + 2 : at + 4])[0], at + 4
        return frame[at:] if ethertype in (0x0800, 0x86DD) else None
    if linktype == 113:  # Linux cooked v1
        return frame[16:] if struct.unpack("!H", frame[14:16])[0] in (0x0800, 0x86DD) else None
    if linktype == 276:  # Linux cooked v2
        return frame[20:] if struct.unpack("!H", frame[0:2])[0] in (0x0800, 0x86DD) else None
    if linktype in (101, 228, 229):  # raw IP
        return frame
    return None


def inspect(packet: bytes, capture: Capture) -> None:
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        header = (packet[0] & 0x0F) * 4
        proto, src, dst, rest = packet[9], packet[12:16], packet[16:20], packet[header:]
    elif version == 6 and len(packet) >= 40:
        proto, src, dst, rest = packet[6], packet[8:24], packet[24:40], packet[40:]
    else:
        return
    capture.ip_packets += 1
    src_ip, dst_ip = ip_of(src), ip_of(dst)
    if DESTINATION_ADDRESS in (src_ip, dst_ip):
        capture.to_destination += 1
    if proto == 6 and len(rest) >= 14:  # TCP
        sport, dport = struct.unpack("!HH", rest[0:4])
        flags = rest[13]
        if flags & 0x02 and not flags & 0x10 and dst_ip == PROXY_GATEWAY and dport in capture.syns_to_proxy:
            capture.syns_to_proxy[dport] += 1
        if 53 in (sport, dport):
            offset = (rest[12] >> 4) * 4
            payload = rest[offset + 2 :]  # TCP DNS has a 2-byte length first
            name = dns_name(payload)
            if name is not None:
                capture.dns_queries += 1
                if name.rstrip(".") == DESTINATION_NAME:
                    capture.dns_for_destination.append(dst_ip)
    elif proto == 17 and len(rest) >= 8:  # UDP
        sport, dport = struct.unpack("!HH", rest[0:4])
        if 53 in (sport, dport):
            name = dns_name(rest[8:])
            if name is not None:
                capture.dns_queries += 1
                if name.rstrip(".") == DESTINATION_NAME:
                    capture.dns_for_destination.append(dst_ip)


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
        capture.error = f"not a pcap file (magic {magic.hex()}; pcapng is not read)"
        return capture
    capture.linktype = struct.unpack(endian + "I", data[20:24])[0] & 0x0FFFFFFF
    at = 24
    while at + 16 <= len(data):
        incl_len = struct.unpack(endian + "I", data[at + 8 : at + 12])[0]
        frame = data[at + 16 : at + 16 + incl_len]
        at += 16 + incl_len
        capture.packets += 1
        try:
            packet = network_payload(capture.linktype, frame)
            if packet:
                inspect(packet, capture)
        except (IndexError, struct.error):
            continue  # a truncated frame
    return capture


# --- logs -------------------------------------------------------------------


UNREADABLE: list[str] = []


def read_lines(path: str, required: bool = False) -> list[str]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError as e:
        if required:
            UNREADABLE.append(f"{os.path.basename(path)}: {e.strerror}")
        return []


def properties(path: str) -> dict[str, str]:
    values = {}
    for line in read_lines(path):
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


TEST_EVENT = re.compile(r"TestRunner\s*:\s*(started|failed|finished): (\S+)\(([\w.$]+)\)")
TEST_EXCEPTION = re.compile(r"TestRunner\s*:\s*(.*)$")


def instrumentation_summary(logcat: list[str], first_lines: int = 2, max_failures: int = 12) -> list[str]:
    """How far the device tests got, from AndroidJUnitRunner's own logcat lines: useful when the
    Gradle run was stopped before it wrote its reports (a hanging test)."""
    started: list[str] = []
    failed: list[str] = []
    finished: set[str] = set()
    excerpts: dict[str, list[str]] = {}
    current: str | None = None
    in_exception = False
    for line in logcat:
        event = TEST_EVENT.search(line)
        if event:
            kind, name = event.group(1), f"{event.group(3).rsplit('.', 1)[-1]}.{event.group(2)}"
            if kind == "started":
                started.append(name)
            elif kind == "failed":
                failed.append(name)
                current = name
            else:
                finished.add(name)
            continue
        if "TestRunner" not in line:
            continue
        if "----- begin exception -----" in line:
            in_exception = True
        elif "----- end exception -----" in line:
            in_exception = False
        elif in_exception and current is not None:
            text = TEST_EXCEPTION.search(line)
            if text and len(excerpts.setdefault(current, [])) < first_lines:
                excerpts[current].append(text.group(1).strip()[:200])
    if not started:
        return ["device tests in logcat: none (the instrumentation logged no test)"]
    unfinished = [name for name in started if name not in finished]
    facts = [f"device tests in logcat: {len(started)} started, {len(failed)} failed, {len(unfinished)} never finished"]
    facts += [f"  never finished: {name}" for name in unfinished]
    for name in failed[:max_failures]:
        facts.append(f"  failed: {name}: {' | '.join(excerpts.get(name, []))}")
    return facts


NETWORK_EVENT = re.compile(r"(ConnectivityService.*[Dd]efault network|got DISCONNECTED|setWifiEnabled)")


def network_changes(logcat: list[str], last: int = 6) -> list[str]:
    """Android's own record of network changes during the run (ConnectivityService, Wi-Fi): the app
    follows none until M7, so a change in the middle of the tests explains a lost route."""
    # ProxyFlow's own VPN networks come and go with every connection; only the uplink's changes count here.
    events = [re.sub(r"\s+", " ", line)[:200] for line in logcat if NETWORK_EVENT.search(line) and "VPN" not in line]
    if not events:
        return ["network changes in logcat: none"]
    return [f"network changes in logcat: {len(events)}; last: {events[-last:]}"]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--pf", required=True, help="the runner-side directory of scripts/e2e_env.sh")
    parser.add_argument("--out", required=True, help="what scripts/e2e_device.sh pulled from the emulator")
    parser.add_argument("--host-pcap", help="the emulator's own capture (-tcpdump)")
    args = parser.parse_args(argv[1:])

    problems: list[str] = []
    facts: list[str] = [f"API {args.api}"]

    # The dump after the tests, or the stream taken while they ran when the dump is shorter (a device that hung).
    dumped = read_lines(os.path.join(args.out, "logcat.txt"))
    streamed = read_lines(os.path.join(args.out, "logcat-live.txt"))
    logcat = dumped if len(dumped) >= len(streamed) else streamed
    if not logcat:
        problems.append("no logcat: neither the dump after the tests nor the stream taken while they ran")
    exchanged = [m.group(1) for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:\s*exchange (\S+)", line))]
    held = [m.group(1) for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:\s*held (\S+)", line))]
    facts.append(
        f"logcat ({'dump after the tests' if logcat is dumped else 'stream taken during the tests'}): "
        f"{len(logcat)} lines, {len(exchanged)} exchanged lines, {len(held)} held lines"
    )
    if not exchanged:
        problems.append("no exchanged line in logcat: the end-to-end tests did not run (or logcat lost their lines)")

    secrets = properties(os.path.join(args.pf, "e2e.properties"))
    for key in ("socks.password", "http.password"):
        secret = secrets.get(key, "")
        if not secret:
            problems.append(f"no {key} in e2e.properties")
            continue
        hits = [line for line in logcat if secret in line]
        if hits:
            tags = sorted({re.sub(r"\s+", " ", line.replace(secret, "<redacted>"))[:120] for line in hits})
            problems.append(f"the test {key} appears {len(hits)} times in logcat: {tags[:3]}")
    facts.append("logcat: neither test password appears" if not any("password appears" in p for p in problems) else "logcat: a password appears")

    # The destination: "<time> <peer> <line>".
    destination = [line.split(" ", 2) for line in read_lines(os.path.join(args.pf, "destination.log"), required=True) if line.count(" ") >= 2]
    lines_at_destination = [parts[2] for parts in destination]
    for line in exchanged:
        count = lines_at_destination.count(line)
        if count != 1:
            problems.append(f"line {line} reached the destination {count} times, expected once")
    for line in held:
        if line in lines_at_destination:
            problems.append(f"held line {line} reached the destination while traffic had to be held")
    facts.append(f"destination: {len(destination)} connections; every exchanged line arrived once; no held line arrived")

    # The proxies' tunnels to the destination.
    squid = read_lines(os.path.join(args.pf, "squid-access.log"), required=True)
    squid_tunnels = [line for line in squid if "TCP_TUNNEL/200" in line and f"CONNECT {DESTINATION_NAME}:{DESTINATION_PORT}" in line]
    squid_by_address = [line for line in squid if f"CONNECT {DESTINATION_ADDRESS}:{DESTINATION_PORT}" in line]
    squid_by_address_tunnels = [line for line in squid_by_address if "TCP_TUNNEL/200" in line]
    squid_denied = [line for line in squid if "TCP_DENIED/407" in line]
    squid_preflight = [line for line in squid if "CONNECT connectivitycheck.gstatic.com:443" in line]
    dante = read_lines(os.path.join(args.pf, "danted.log"), required=True)
    # A session's first line ("[:"); Dante names the endpoint it connected to, by address or by the name it was given.
    dante_endpoint = "(?:" + "|".join(re.escape(f"{host}.{DESTINATION_PORT}") for host in (DESTINATION_ADDRESS, DESTINATION_NAME)) + ")"
    dante_tunnels = [line for line in dante if re.search(r"tcp/connect \[: .*" + dante_endpoint, line) and "block(" not in line]
    dante_named = any(DESTINATION_NAME in line for line in dante)
    dante_auth_failed = [line for line in dante if "authentication failed" in line.lower()]
    facts.append(
        f"Squid: {len(squid_tunnels)} tunnels to {DESTINATION_NAME}:{DESTINATION_PORT} (asked by name), "
        f"{len(squid_by_address)} by address, {len(squid_preflight)} pre-flight CONNECTs to connectivitycheck.gstatic.com:443, "
        f"{len(squid_denied)} refused sign-ins (407)"
    )
    facts.append(
        f"Dante: {len(dante_tunnels)} tunnels to {DESTINATION_ADDRESS}:{DESTINATION_PORT}"
        f" (its log {'names' if dante_named else 'does not name'} {DESTINATION_NAME}), {len(dante_auth_failed)} refused sign-in lines"
    )
    if not squid_tunnels:
        problems.append("Squid opened no tunnel to the destination by name")
    if squid_by_address:
        problems.append(f"Squid was asked for the destination by address {len(squid_by_address)} times: the phone resolved the name itself")
    if not dante_tunnels:
        problems.append("Dante opened no tunnel to the destination")
    # A tunnel asked for by address is a problem of its own (above), but it did go through a proxy.
    through_proxies = len(squid_tunnels) + len(squid_by_address_tunnels) + len(dante_tunnels)
    if len(destination) != through_proxies:
        problems.append(
            f"{len(destination)} connections reached the destination but the proxies opened {through_proxies} tunnels to it: "
            "something reached it without a proxy"
        )
    else:
        facts.append(f"every connection that reached the destination came through a proxy ({through_proxies})")

    # The sink: only lines sent while traffic had to be held go there. A connection it logged, or a tunnel a proxy was
    # asked to open to it, is held traffic that got out: at once, or a held packet replayed later through a proxy
    # (whose own connection carries no line, so the destination's line check could not see it).
    sink = read_lines(os.path.join(args.pf, "sink.log"), required=True)
    sink_requests = [
        line for line in squid + dante
        if f"{DESTINATION_ADDRESS}:{SINK_PORT}" in line or f"{DESTINATION_ADDRESS}.{SINK_PORT}" in line
    ]
    facts.append(
        f"sink {DESTINATION_ADDRESS}:{SINK_PORT} for held lines: {len(sink)} connections, {len(sink_requests)} proxy requests "
        f"({len(held)} held lines sent)"
    )
    if sink:
        problems.append(f"held traffic got out: the sink logged {len(sink)} connections: {[line[:120] for line in sink[:3]]}")
    if sink_requests:
        problems.append(f"held traffic reached a proxy later: {len(sink_requests)} requests for the sink: {[line[:160] for line in sink_requests[:3]]}")

    # How the logs look, so a format the checks above do not match shows up in the annotation.
    for name, lines in (("squid-access.log", squid), ("danted.log", dante)):
        sample = [line for line in lines if "connect" in line.lower()][-3:] or lines[-3:]
        facts.append(f"{name}: {len(lines)} lines; last: {[line[:220] for line in sample]}")
    network = read_lines(os.path.join(args.out, "guest-network.txt"))
    facts += [f"phone network: {line}" for line in network]
    if network and network[0].startswith("uplink: none"):
        problems.append("the phone's uplink never reached the runner, so no test could reach a proxy")
    guest = read_lines(os.path.join(args.out, "guest-capture.txt"))
    if guest:
        facts.append("in-phone capture: " + " | ".join(guest))
    # tcpdump inside the phone counts as coverage only if it was still running after the tests.
    guest_spans_tests = any(line.startswith("tcpdump at the end: ") and "none" not in line for line in guest)
    # Per interface where the device script recorded it: a phone capture spans the tests only if its own tcpdump
    # still ran at the end (another interface's surviving process says nothing about it).
    alive_at_end = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the end on:")), None)
    alive_at_start = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the start on:")), None)
    if UNREADABLE:
        problems.append(f"unreadable evidence: {UNREADABLE}")

    # Packet captures.
    paths = sorted(glob.glob(os.path.join(args.out, "guest-*.pcap")))
    if args.host_pcap:
        paths.insert(0, args.host_pcap)
    captures = [read_capture(path) for path in paths]
    covering = 0
    for capture in captures:
        name = os.path.basename(capture.path)
        if capture.error:
            facts.append(f"capture {name}: unreadable ({capture.error})")
            continue
        facts.append(
            f"capture {name}: link type {capture.linktype}, {capture.packets} packets ({capture.ip_packets} IP), "
            f"SYNs to the proxies {capture.syns_to_proxy}, DNS queries {capture.dns_queries}, "
            f"to/from {DESTINATION_ADDRESS}: {capture.to_destination}, DNS queries for {DESTINATION_NAME}: {len(capture.dns_for_destination)}"
        )
        partial = guest_capture_partial(os.path.basename(capture.path), guest_spans_tests, alive_at_end, alive_at_start)
        if partial:
            facts.append(f"capture {name}: tcpdump was not running after the tests, so it does not count as coverage")
        elif capture.covers_uplink:
            covering += 1
        if capture.to_destination:
            problems.append(f"capture {name}: {capture.to_destination} packets to or from the destination {DESTINATION_ADDRESS} on the uplink")
        if capture.dns_for_destination:
            problems.append(f"capture {name}: DNS queries for {DESTINATION_NAME} left the phone (to {sorted(set(capture.dns_for_destination))})")
    if covering == 0:
        problems.append("no packet capture saw the app's connections to both proxies, so none shows the uplink")
    else:
        facts.append(f"{covering} of {len(captures)} captures saw the app's connections to both proxies (they cover the uplink)")

    facts += instrumentation_summary(logcat)
    facts += network_changes(logcat)
    # What the tests did about the emulator rather than the app: an attempt retried after the phone's own uplink
    # dropped ("no route to the proxy"), and "isn't responding" dialogs of other apps dismissed.
    retried = sum(1 for line in logcat if re.search(r"ProxyFlowE2E\s*:\s*uplink dropped", line))
    dismissed = sum(1 for line in logcat if re.search(r"ProxyFlowE2E\s*:\s*dismissed an app-not-responding dialog", line))
    facts.append(f"emulator hiccups the tests worked around: {retried} attempts retried after the uplink dropped, {dismissed} not-responding dialogs dismissed")

    # The VPN layer's own account of what happened (connection steps, service starts and stops).
    lifecycle = [m.group(1) for line in logcat if (m := re.search(r"ProxyFlowVpn\s*:\s*(.*)$", line))]
    lifecycle_tail = "\n".join(lifecycle[-LIFECYCLE_LINES:])[-LIFECYCLE_CHARS:]

    token = os.urandom(16).hex()
    print(f"::stop-commands::{token}")  # the logs come from the code under test
    print("\n".join(facts))
    print("\n".join(f"PROBLEM {p}" for p in problems) or "OK")
    print(f"VPN lifecycle: {len(lifecycle)} lines\n{lifecycle_tail}")
    print(f"::{token}::")
    print(f"::notice title=M5 VPN lifecycle (API {args.api}), last of {len(lifecycle)} lines::{escape(lifecycle_tail or '(none)')}")
    if problems:
        annotate("error", f"M5 end-to-end evidence (API {args.api})", problems + facts)
        return 1
    annotate("notice", f"M5 end-to-end evidence (API {args.api})", facts)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
