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

For the periods ProxyFlow was connected (from the VPN log in logcat; each covering capture lined
up with logcat by the phone's own connections to its proxy), every lookup and every new
connection, datagram or ping that left the phone on its own network must be accounted for: to
its proxy, its network's resolver or Android's network and time checks; or owned, by the
phone's socket samples (e2e_device.sh), by an app Android lets leave a VPN (a system uid, or an
app granted a network bypass permission), which is reported. The shell, ProxyFlow or any other
owner fails the run; what no sample caught passes as such an app's only when Android's own view
of the VPN (logged by the tests after each connection) routed every uid but ProxyFlow's into it
and ProxyFlow's own sockets reached nothing but its proxy. Both of those are checked on their own
too. See docs/m6/routing-and-limitations.md section 7.

Usage: m6_evidence.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import collections
import datetime
import glob
import ipaddress
import os
import re
import struct
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m5_evidence import annotate, dns_name, escape, guest_capture_partial, ip_of, network_payload  # noqa: E402

LAB_NAMES = ("proxyflow.test", "secure.proxyflow.test", "proxyflow6.test", "missing.proxyflow.test")
PROXY_NAME = "proxy.proxyflow.test"
LAB_ADDRESSES = tuple(str(ipaddress.ip_address(a)) for a in ("10.99.0.1", "10.99.0.2", "fd99::1"))  # reachable only through a proxy
GATEWAY = "10.0.2.2"  # the runner as the emulator sees it: the proxies, and the LAN destination
LAN_PORT = 8082
SOCKS_PORTS = (1080, 1090)  # Dante, and the gate in front of it
HTTP_PORT = 3128
TLS_PORTS = (3129, 1443, 3130, 3131, 3132)
# Every port the app reaches on the runner as a proxy (with the M5 tests' silent and closed ones), and the gate's control
# port, which the tests themselves use from ProxyFlow's own process (outside its VPN, by design).
PROXY_SIDE_PORTS = SOCKS_PORTS + (HTTP_PORT,) + TLS_PORTS + (1081, 1082, 1091)
# The emulator network's own DNS servers: 10.0.2.3 forwards to the lab's uplink resolver (its log is checked below),
# fec0::3 is its IPv6 counterpart.
NETWORK_RESOLVERS = ("10.0.2.3", "fec0::3")
# The emulator network's router and resolvers: never the phone, whatever prefix they share with it.
INFRASTRUCTURE = frozenset((GATEWAY, "10.0.2.1", "10.0.2.3", "fec0::2", "fec0::3", "fe80::2"))
# Android's own traffic outside VPNs while one is up: NetworkMonitor checking the phone's network (its probe hosts, with
# their fallbacks) and the time service. Lookups of these, and their HTTP(S) and NTP, are expected on the uplink.
ANDROID_NAMES = frozenset(
    ("connectivitycheck.gstatic.com", "www.google.com", "play.googleapis.com", "clients1.google.com",
     "clients3.google.com", "connectivitycheck.android.com", "time.android.com")
)

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
    ipv6_packets: int = 0
    lan_syns: int = 0
    udp_to_lab: collections.Counter = field(default_factory=collections.Counter)  # "address:port" -> datagrams
    echo_requests: collections.Counter = field(default_factory=collections.Counter)  # destination -> ICMP/ICMPv6 echo requests
    dns_names: collections.Counter = field(default_factory=collections.Counter)
    socks_targets: collections.Counter = field(default_factory=collections.Counter)  # "name x" / "IPv4 x" / "IPv6 x"
    connect_targets: collections.Counter = field(default_factory=collections.Counter)
    tls_port_streams: collections.Counter = field(default_factory=collections.Counter)  # (port, "TLS"|"plain")
    plaintext_on_tls: list[str] = field(default_factory=list)
    first_payload_seen: set = field(default_factory=set)
    segments_seen: set = field(default_factory=set)
    # For the connected periods: when each new TCP connection, UDP datagram and echo request left, and each DNS query.
    # (time, src, dst, "TCP"|"UDP"|"ICMP"|"IP<protocol>", port, source port of a TCP SYN or a UDP datagram)
    events: list = field(default_factory=list)
    hops: dict = field(default_factory=dict)  # an event -> the packet's TTL or hop limit, for the report
    dns_queries: list = field(default_factory=list)  # (time, name, src, source port, dst, "UDP"|"TCP")
    dns_answers: dict = field(default_factory=lambda: collections.defaultdict(set))  # name -> addresses answered
    dns_streams: dict = field(default_factory=dict)  # DNS over TCP: the client's bytes so far, per connection

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


def skip_name(m: bytes, at: int) -> int:
    while at < len(m):
        length = m[at]
        if length & 0xC0 == 0xC0:
            return at + 2
        if length == 0:
            return at + 1
        at += 1 + length
    raise IndexError("name runs past the message")


def dns_answer_addresses(message: bytes) -> tuple[str | None, list[str]]:
    """The question's name and the A/AAAA addresses of a DNS response."""
    if len(message) < 12 or not message[2] & 0x80:
        return None, []
    questions, answers = struct.unpack("!HH", message[4:8])
    labels, at = [], 12
    while questions and at < len(message) and message[at] and not message[at] & 0xC0:
        labels.append(message[at + 1 : at + 1 + message[at]].decode("ascii", "replace"))
        at += 1 + message[at]
    name = ".".join(labels) if labels else None
    at = 12
    for _ in range(questions):
        at = skip_name(message, at) + 4
    found = []
    for _ in range(answers):
        at = skip_name(message, at)
        if at + 10 > len(message):
            break  # the phone's own captures keep 400 bytes of each packet: later answers may be cut off
        rtype, _, _, length = struct.unpack("!HHIH", message[at : at + 10])
        at += 10
        if at + length > len(message):
            break
        if (rtype == 1 and length == 4) or (rtype == 28 and length == 16):
            found.append(ip_of(message[at : at + length]))
        at += length
    return (name.rstrip(".").lower() if name else None), found


def dns_over_tcp(capture: "Capture", flow: tuple, payload: bytes, when) -> None:
    """Reassembles the client's side of a DNS-over-TCP connection (the length and the query often come in two
    segments) and records each query's name."""
    buffer = capture.dns_streams.setdefault(flow, bytearray())
    if len(buffer) > 8192:
        return
    buffer += payload
    while len(buffer) >= 2:
        length = struct.unpack("!H", buffer[:2])[0]
        if len(buffer) < 2 + length:
            return
        name = dns_name(bytes(buffer[2 : 2 + length]))
        if name is not None:
            capture.dns_names[name.rstrip(".")] += 1
            capture.dns_queries.append((when, name.rstrip(".").lower(), flow[0], flow[1], flow[2], "TCP"))
        del buffer[: 2 + length]


IPV6_EXTENSIONS = (0, 43, 44, 60)  # hop-by-hop, routing, fragment, destination options


def upper_layer(next_header: int, rest: bytes) -> tuple[int, bytes]:
    """The protocol and payload after any IPv6 extension headers (a later fragment has no upper-layer header)."""
    while next_header in IPV6_EXTENSIONS and len(rest) >= 8:
        if next_header == 44:
            if struct.unpack("!H", rest[2:4])[0] & 0xFFF8:  # not the first fragment
                return -1, b""
            next_header, rest = rest[0], rest[8:]
        else:
            next_header, rest = rest[0], rest[(rest[1] + 1) * 8 :]
    return next_header, rest


def inspect(packet: bytes, capture: Capture, when: datetime.datetime | None = None) -> None:
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        header = (packet[0] & 0x0F) * 4
        proto, src, dst, rest, hops = packet[9], ip_of(packet[12:16]), ip_of(packet[16:20]), packet[header:], packet[8]
        if struct.unpack("!H", packet[6:8])[0] & 0x1FFF:  # a later fragment: no upper-layer header
            proto = -1
    elif version == 6 and len(packet) >= 40:
        src, dst, hops = ip_of(packet[8:24]), ip_of(packet[24:40]), packet[7]
        proto, rest = upper_layer(packet[6], packet[40:])
        capture.ipv6_packets += 1
    else:
        return
    for address in LAB_ADDRESSES:
        if address in (src, dst):
            capture.lab_packets[address] += 1
    if (proto == 1 and rest[:1] == b"\x08") or (proto == 58 and rest[:1] == b"\x80"):
        capture.echo_requests[dst] += 1
        capture.events.append((when, src, dst, "ICMP", 0, None))
    elif proto not in (-1, 1, 6, 17, 58):  # any other protocol (GRE, ESP, SCTP...): nothing ProxyFlow sends itself
        capture.events.append((when, src, dst, f"IP{proto}", 0, None))
    if proto == 6 and len(rest) >= 20:
        sport, dport, seq = struct.unpack("!HHI", rest[0:8])
        flags = rest[13]
        offset = (rest[12] >> 4) * 4
        payload = rest[offset:]
        if flags & 0x02 and not flags & 0x10:
            capture.syns[(dst, dport)] += 1
            capture.events.append((when, src, dst, "TCP", dport, sport))
            capture.hops[capture.events[-1]] = hops
            if dst == GATEWAY and dport == LAN_PORT:
                capture.lan_syns += 1
        if not payload:
            return
        flow = (src, sport, dst, dport)
        segment = (flow, seq)
        if segment in capture.segments_seen:
            return  # a retransmission
        capture.segments_seen.add(segment)
        if dport == 53:
            dns_over_tcp(capture, flow, payload, when)
            return
        if dst != GATEWAY:
            return
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
        capture.events.append((when, src, dst, "UDP", dport, sport))
        capture.hops[capture.events[-1]] = hops
        if dport == 53:
            name = dns_name(rest[8:])
            if name is not None:
                capture.dns_names[name.rstrip(".")] += 1
                capture.dns_queries.append((when, name.rstrip(".").lower(), src, sport, dst, "UDP"))
            elif len(rest) > 8 + 12 and not rest[8 + 2] & 0x80:
                # a long name the capture's length limit cut off
                capture.dns_queries.append((when, "<unreadable query>", src, sport, dst, "UDP"))
        elif sport == 53:
            name, addresses = dns_answer_addresses(rest[8:])
            if name:
                capture.dns_answers[name].update(addresses)
        if dst in LAB_ADDRESSES:
            capture.udp_to_lab[f"[{dst}]:{dport}" if ":" in dst else f"{dst}:{dport}"] += 1


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
    nanoseconds = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
    while at + 16 <= len(data):
        seconds, fraction, incl_len = struct.unpack(endian + "III", data[at : at + 12])
        frame = data[at + 16 : at + 16 + incl_len]
        at += 16 + incl_len
        capture.packets += 1
        when = datetime.datetime.fromtimestamp(seconds + fraction / (1e9 if nanoseconds else 1e6), datetime.timezone.utc).replace(tzinfo=None)
        try:
            packet = network_payload(linktype, frame)
            if packet:
                inspect(packet, capture, when)
        except (IndexError, struct.error, ValueError):
            continue  # a packet cut off by the capture's length limit, or not what its header says
    return capture


# --- what the phone looked up itself while connected ---------------------------

MONTHS = {name: i for i, name in enumerate(("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), 1)}
MARGIN = datetime.timedelta(seconds=2)  # the emulator's clock and the runner's agree to about a second


def logcat_time(line: str, year: int) -> datetime.datetime | None:
    m = re.match(r"(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})", line)
    if not m:
        return None
    month, day, hour, minute, second, ms = (int(g) for g in m.groups())
    return datetime.datetime(year, month, day, hour, minute, second, ms * 1000)


def dnsmasq_time(line: str, year: int) -> datetime.datetime | None:
    m = re.match(r"([A-Z][a-z]{2}) +(\d{1,2}) (\d\d):(\d\d):(\d\d) ", line)
    if not m or m.group(1) not in MONTHS:
        return None
    local = datetime.datetime(year, MONTHS[m.group(1)], int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)))
    return local.astimezone(datetime.timezone.utc).replace(tzinfo=None)  # dnsmasq logs the runner's local time


def connected_windows(logcat: list[str], year: int) -> tuple[list[tuple[datetime.datetime, datetime.datetime, str]], list[datetime.datetime]]:
    """From the VPN layer's log: when apps were routed into the VPN (Connected until Disconnect, revocation or the
    service's end; a period still open when logcat ends closes there), and when each connection attempt began (the app
    connects to its proxy right then). A test about to have Android revoke the VPN logs it first ("ProxyFlowE2E:
    revoking"), which ends the period: Android may take the VPN away before ProxyFlow hears of it, and apps then use the
    normal network by design. Each period carries what ended it, for the report."""
    windows, starts, begun, label, last = [], [], None, "", None
    for line in logcat:
        when = logcat_time(line, year)
        if when is None:
            continue
        last = when
        m = re.search(r"ProxyFlowVpn\s*:\s*(.*)$", line)
        marker = re.search(r"ProxyFlowE2E\s*:\s*revoking", line)
        if marker and begun is not None:
            windows.append((begun, when, f"{label} until the test had Android revoke the VPN"))
            begun = None
            continue
        if not m:
            continue
        event = m.group(1)
        if event.startswith("connect ") and "no such profile" not in event:
            starts.append(when)
        elif event.startswith("connected ") and begun is None:
            begun, label = when, f"connected at {when:%H:%M:%S}"
        elif begun is not None and event.startswith(("disconnect", "revoked", "service destroyed")):
            windows.append((begun, when, f"{label} until {event[:40]!r}"))
            begun = None
    if begun is not None and last is not None:
        windows.append((begun, last, f"{label} until logcat ends"))
    return windows, starts


def uplink_queries(lines: list[str], year: int) -> list[tuple[datetime.datetime, str, str]]:
    found = []
    for line in lines:
        m = re.search(r"query\[(\w+)\] (\S+) from", line)
        when = dnsmasq_time(line, year) if m else None
        if when is not None:
            found.append((when, m.group(2).lower().rstrip("."), m.group(1)))
    return found


def clocks_agree(queries: list, starts: list[datetime.datetime]) -> tuple[bool, str]:
    """Each lookup of the proxy's own name follows a connection attempt within a second or two: the runner's clock
    (the DNS server's log, the emulator's capture) and the phone's (logcat) line up when the median gap is small."""
    offsets = []
    for when, name, _ in queries:
        if name == PROXY_NAME:
            before = [start for start in starts if start <= when + MARGIN]
            offsets.append((when - max(before)).total_seconds() if before else 99.0)
    offsets.sort()
    if not offsets or abs(offsets[len(offsets) // 2]) > 3:
        return False, f"the emulator's and the runner's clocks could not be lined up (lookup offsets {offsets[:5]} s)"
    return True, f"clocks agree within {abs(offsets[len(offsets) // 2]):.1f} s"


def inside(when: datetime.datetime | None, windows: list) -> bool:
    return period_of(when, windows) is not None


def period_of(when: datetime.datetime | None, windows: list) -> str | None:
    """The connected period [when] falls in, at least MARGIN inside it, or None."""
    if when is None:
        return None
    return next((label for start, end, label in windows if start + MARGIN <= when <= end - MARGIN), None)


def capture_offset(capture: "Capture", starts: list[datetime.datetime], phone: "Phone") -> tuple[float | None, str]:
    """How far the capture's clock is from logcat's, in seconds, measured on the capture itself: right after each
    connection attempt begins (logcat), the phone opens a connection to its proxy (the capture). Both clocks are UTC
    and agree within a second or two, so each attempt is matched with the phone's nearest SYN to a proxy port within
    3 s. The capture is lined up only if four in five attempts matched, their gaps agree (half of them within 1.5 s of
    each other: the emulator's own capture, on the runner's clock, scatters by a few tenths of a second) and the offset
    is under 2 s: a wrong lock onto other connections would scatter the gaps over the whole 6 s."""
    syns = sorted(when for (when, src, dst, kind, port, _) in capture.events
                  if when is not None and kind == "TCP" and src in phone and dst == GATEWAY and port in PROXY_SIDE_PORTS)
    gaps = []
    for start in starts:
        near = [(t - start).total_seconds() for t in syns if abs((t - start).total_seconds()) <= 3]
        if near:
            gaps.append(min(near, key=abs))
    if not gaps or len(gaps) < 0.8 * len(starts):
        return None, f"only {len(gaps)} of {len(starts)} connection attempts have the phone's SYN to its proxy within 3 s"
    gaps.sort()
    median, spread = gaps[len(gaps) // 2], gaps[(3 * len(gaps)) // 4] - gaps[len(gaps) // 4]
    if spread > 1.5 or abs(median) > 2:
        return None, f"{len(gaps)} of {len(starts)} attempts matched, but their gaps spread over {spread:.2f} s around {median:+.2f} s"
    return median, f"{len(gaps)} of {len(starts)} connection attempts matched, gaps within {spread:.2f} s"


@dataclass
class Phone:
    """The phone's own addresses on its uplink, and the networks they are in (IPv4 at most a /24, IPv6 a /64): an
    address the phone took during the tests (a new temporary IPv6 address) still counts as the phone's."""
    addresses: set = field(default_factory=set)
    networks: set = field(default_factory=set)

    def add(self, interface: str) -> None:
        try:
            address = ipaddress.ip_interface(interface)
        except ValueError:
            return
        self.addresses.add(str(address.ip))
        widest = 24 if address.version == 4 else 64
        self.networks.add(address.network if address.network.prefixlen >= widest else ipaddress.ip_interface(f"{address.ip}/{widest}").network)

    def __contains__(self, address: str) -> bool:
        if address in self.addresses:
            return True
        if address in INFRASTRUCTURE:
            return False
        try:
            ip = ipaddress.ip_address(address)
        except ValueError:
            return False
        return any(ip in network for network in self.networks)


def phone_of(capture: "Capture", network: list[str], sockets: "Sockets") -> Phone:
    """Where the phone's SYNs to the proxies come from, every address the device script recorded on the uplink
    interfaces (before and after the tests) and every address the socket sampler saw during them."""
    phone = Phone()
    for (_, src, dst, kind, port, _) in capture.events:
        if kind == "TCP" and dst == GATEWAY and port in PROXY_SIDE_PORTS:
            phone.addresses.add(src)
    for line in network:
        if "addresses" not in line.split(":", 1)[0]:
            continue
        for item in line.split(":", 1)[1].split(";"):
            parts = item.split()
            if len(parts) == 2 and not parts[0].startswith(("lo", "dummy", "tun")) and "/" in parts[1]:
                phone.add(parts[1])
    for interface in sockets.addresses:
        phone.add(interface)
    return phone


def explained(dst: str, kind: str, port: int, android_addresses: set) -> bool:
    """Traffic from the phone on its own network that is expected while ProxyFlow is connected."""
    address = ipaddress.ip_address(dst)
    if address.is_multicast or address.is_link_local or dst == "255.255.255.255" or (kind == "UDP" and port in (67, 68)):
        return True  # the network itself: DHCP, neighbour discovery, multicast
    if dst == GATEWAY and kind == "TCP" and port in PROXY_SIDE_PORTS:
        return True  # ProxyFlow to its proxy (and the tests to the gate's control port)
    if dst in NETWORK_RESOLVERS and (port == 53 or (kind == "TCP" and port == 853)):
        return True  # lookups on the phone's network (names checked apart) and Android's Private DNS check of it
    return dst in android_addresses and ((kind == "TCP" and port in (80, 443)) or (kind == "UDP" and port == 123))


# --- who owned a connection (the phone's kernel tables, sampled by e2e_device.sh) ------

PLACEHOLDERS = ipaddress.ip_network("198.18.0.0/15")


def endpoint(text: str) -> tuple[str, int]:
    """An address:port of /proc/net/{tcp,udp}[6]: hex, each 32-bit word little-endian; IPv4-mapped IPv6 as IPv4."""
    address, port = text.split(":")
    raw = bytes.fromhex(address)
    if len(raw) == 4:
        ip = ipaddress.ip_address(raw[::-1])
    elif len(raw) == 16:
        ip = ipaddress.ip_address(b"".join(raw[i : i + 4][::-1] for i in range(0, 16, 4)))
        ip = ip.ipv4_mapped or ip
    else:
        raise ValueError(f"not an address: {address}")
    return str(ip), int(port, 16)


@dataclass
class Sockets:
    """The phone's sockets as the device script sampled them, about four times a second: every TCP socket an app or
    a daemon holds open (not listening; one in TIME-WAIT or closed has no owner left) and every connected UDP
    socket, with its owner's uid; and the phone's addresses every few seconds."""
    spans: dict = field(default_factory=dict)  # (proto, local ip, local port, remote ip, remote port, uid) -> [first, last]
    snapshots: list = field(default_factory=list)
    addresses: set = field(default_factory=set)  # "address/prefix"
    fractional: bool = False

    def owners(self, proto: str, src: str, sport: int, dst: str, dport: int, when: datetime.datetime) -> list[int]:
        """The uids of the sockets with this connection's own ends, seen around [when]."""
        window = 1.5 if self.fractional else 2.5  # whole-second samples (an older shell) need more slack
        return sorted({
            uid for (p, lip, lport, rip, rport, uid), (first, last) in self.spans.items()
            if p == proto and lport == sport and rip == dst and rport == dport and lip in (src, "0.0.0.0", "::")
            and (first - when).total_seconds() <= window and (when - last).total_seconds() <= window
        })

    def near(self, when: datetime.datetime) -> int:
        return sum(1 for t in self.snapshots if abs((t - when).total_seconds()) <= 1)

    def coverage(self) -> str:
        if not self.snapshots:
            return "no samples"
        gaps = [(b - a).total_seconds() for a, b in zip(self.snapshots, self.snapshots[1:])]
        return (f"{len(self.snapshots)} snapshots from {self.snapshots[0]:%H:%M:%S} to {self.snapshots[-1]:%H:%M:%S}, "
                f"the longest gap {max(gaps, default=0):.1f} s")


def socket_samples(out: str) -> Sockets:
    sockets, when = Sockets(), None
    for line in read(os.path.join(out, "phone-connecting.txt")):
        if line.startswith("T "):
            text = line[2:].strip()
            try:
                when = datetime.datetime.fromtimestamp(float(text), datetime.timezone.utc).replace(tzinfo=None)
            except ValueError:
                when = None
                continue
            sockets.fractional = sockets.fractional or "." in text
            sockets.snapshots.append(when)
            continue
        if line.startswith("A "):  # ip -o addr: "A 2: eth0    inet 10.0.2.15/24 brd ..."
            m = re.match(r"A \d+: (\S+)\s+inet6? (\S+)", line)
            if m and not m.group(1).startswith(("lo", "dummy", "tun")):
                sockets.addresses.add(m.group(2))
            continue
        parts = line.split()
        proto = "tcp"
        if parts and parts[0] in ("tcp", "tcp6", "udp", "udp6"):
            proto, parts = ("udp" if parts[0].startswith("udp") else "tcp"), parts[1:]
        if when is None or len(parts) < 10 or ":" not in parts[1] or ":" not in parts[2]:
            continue
        state, inode = parts[3], parts[9]
        if inode == "0" or (proto == "tcp" and state in ("0A", "06", "07")) or (proto == "udp" and state != "01"):
            continue
        try:
            (lip, lport), (rip, rport), uid = endpoint(parts[1]), endpoint(parts[2]), int(parts[7])
        except ValueError:
            continue
        span = sockets.spans.setdefault((proto, lip, lport, rip, rport, uid), [when, when])
        span[0], span[1] = min(span[0], when), max(span[1], when)
    return sockets


def package_names(out: str) -> dict[int, str]:
    names: dict[int, list[str]] = {}
    for line in read(os.path.join(out, "packages-uids.txt")):
        m = re.match(r"package:(\S+) uid:(\d+)", line.strip())
        if m:
            names.setdefault(int(m.group(2)), []).append(m.group(1))
    return {uid: ",".join(sorted(packages))[:100] for uid, packages in names.items()}


# The tests' own traffic, which the VPN must carry whatever kind of uid it is: their commands run as the shell, through
# UiAutomation, whatever adbd runs as (root sockets are Android's daemons: netd's resolver on Android 10).
TEST_UIDS = (2000,)
OWN_PACKAGES = ("app.proxyflow",)  # ProxyFlow (and its test APK): its only direct traffic is to its proxy
# What lets an app leave a VPN that does not allow bypassing: Android lets it choose another network (netd's system
# permission) if it holds one of these, as privileged apps such as Google Play services may.
NETWORK_BYPASS_PERMISSIONS = frozenset(("CONNECTIVITY_USE_RESTRICTED_NETWORKS", "NETWORK_STACK", "MAINLINE_NETWORK_STACK"))


def network_grants(out: str) -> dict[int, set[str]]:
    """The network permissions each uid was granted, from dumpsys package (the device script keeps the package and
    shared-user headers, their uids and the granted network permissions)."""
    grants: dict[int, set[str]] = {}
    uid = None
    for line in read(os.path.join(out, "packages-network-permissions.txt")):
        if re.search(r"(?:Package|SharedUser) \[", line):
            uid = None
            continue
        m = re.search(r"\b(?:userId|appId)=(\d+)", line)
        if m:
            uid = int(m.group(1))
            grants.setdefault(uid, set())
            continue
        m = re.search(r"android\.permission\.(\w+): granted=true", line)
        if m and uid is not None:
            grants[uid].add(m.group(1))
    return grants


@dataclass
class Owners:
    names: dict
    grants: dict
    own: set  # ProxyFlow's uids: from its test log, and from its packages if still installed

    def may_bypass(self, uid: int) -> bool:
        """Whether Android lets [uid] use another network than the VPN: system uids and apps with a network bypass
        permission; never the tests (shell, root) or ProxyFlow."""
        if uid in TEST_UIDS or uid in self.own:
            return False
        return uid < 10000 or bool(self.grants.get(uid, set()) & NETWORK_BYPASS_PERMISSIONS)

    def label(self, uid: int) -> str:
        name = self.names.get(uid) or ("a system uid" if uid < 10000 else "no package: an app removed after the tests")
        if uid in self.own:
            name += "; ProxyFlow"
        bypass = sorted(self.grants.get(uid, set()) & NETWORK_BYPASS_PERMISSIONS)
        return f"uid {uid} ({name}{'; ' + ', '.join(bypass) if bypass else ''})"


def own_uids(logcat: list[str], names: dict) -> set[int]:
    """ProxyFlow's uids: the one its tests log, any still installed under its packages, and their SDK sandbox uids."""
    found = {int(m.group(1)) for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:.*\bown uid (\d+)", line))}
    found |= {uid for uid, name in names.items() if any(n.startswith(OWN_PACKAGES) for n in name.split(","))}
    return found | {uid + 10000 for uid in found if 10000 <= uid < 20000}


def androids_views(logcat: list[str]) -> tuple[list[str], list[str]]:
    """What Android said about ProxyFlow's VPN, as the device tests logged it after each connection (the VPN network's
    capabilities and DNS servers, read with the shell's permissions): the uids it routes into the VPN, whether apps may
    bypass it (Android 13 and later say), and its DNS servers. Returns each readable view's summary and the problems."""
    views, problems, unreadable = [], [], []
    for line in logcat:
        m = re.search(r"ProxyFlowE2E\s*:\s*android's view of the VPN \(own uid (\d+)\): (.*)$", line)
        if not m:
            continue
        own, text = int(m.group(1)), m.group(2)
        uids = re.search(r"Uids: <([^>]*)>", text)
        if not uids:
            unreadable.append(text[:300])
            continue
        ranges = sorted((int(a), int(b)) for a, b in re.findall(r"(\d+)-(\d+)", uids.group(1)))
        gaps, next_uid = [], 0
        for first, last in ranges:
            if first > next_uid and next_uid <= 99999:
                gaps.append((next_uid, min(first - 1, 99999)))
            next_uid = max(next_uid, last + 1)
        if next_uid <= 99999:
            gaps.append((next_uid, 99999))
        # Since Android 13 an app's SDK sandbox (its uid + 10000) is left out of the app's VPN with the app.
        sandbox = own + 10000 if 10000 <= own < 20000 else None
        with_sandbox = [(own, own)] + ([(sandbox, sandbox)] if sandbox else [])
        found = []
        if gaps not in ([(own, own)], with_sandbox):
            found.append(f"Android's VPN leaves out uids {gaps[:4]}, not only ProxyFlow's ({own}" + (f" and its SDK sandbox's, {sandbox})" if sandbox else ")"))
        bypass = re.search(r"bypassable=(\w+)", text)
        if bypass and bypass.group(1) != "false":
            found.append(f"Android says apps may bypass the VPN (bypassable={bypass.group(1)})")
        dns = re.search(r"DnsAddresses: \[([^\]]*)\]", text)
        if dns and "198.18.0.2" not in dns.group(1):
            found.append(f"the VPN's DNS servers are [{dns.group(1).strip()}]")
        problems += found
        views.append(
            f"every uid but {own}" + (f" and its SDK sandbox {sandbox}" if sandbox and gaps == with_sandbox else "")
            + (f", bypassable={bypass.group(1)}" if bypass else "") + (", DNS 198.18.0.2" if dns and not found else "")
        )
    if unreadable:
        views.append(f"{len(unreadable)} without the routed uids, the first: {unreadable[0]!r}")
    return views, problems


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
    line_problems = [p for p in problems if p.startswith(("line ", "sent line "))]
    if sent and not line_problems:
        facts.append("lines: every sent line arrived once where it was sent; no refused line arrived anywhere")
    else:
        facts.append(f"lines: {len(line_problems)} problems (listed above the facts)")

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
        facts.append(f"HTTPS destination: {len(tls_destination)} TLS sessions, {len(wrong_sni)} not for secure.proxyflow.test")
    else:
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
    # While connected: the periods from the VPN layer's log (checked against the captures and the uplink DNS log below).
    year = datetime.datetime.now(datetime.timezone.utc).year
    windows, starts = connected_windows(logcat, year)
    queried = uplink_queries(after("dns-uplink.log"), year)
    span = sum((end - start).total_seconds() for start, end, _ in windows)
    network = read(os.path.join(args.out, "guest-network.txt"))
    sockets = socket_samples(args.out)
    names = package_names(args.out)
    owners = Owners(names, network_grants(args.out), set())
    owners.own = own_uids(logcat, names)
    to_placeholders = collections.Counter(
        f"{rip}:{rport} {owners.label(uid)}" for (proto, _, _, rip, rport, uid) in sockets.spans
        if proto == "tcp" and ipaddress.ip_address(rip) in PLACEHOLDERS
    )
    facts.append(
        f"the phone's sockets (its kernel's tables, {sockets.coverage()}): to placeholder addresses, by owner: "
        f"{dict(to_placeholders.most_common(8)) or 'none seen'}"
    )
    # ProxyFlow is outside its own VPN: its sockets may reach nothing but its proxy (and the tests' gate control port).
    own_sockets = [key for key in sockets.spans if key[5] in owners.own]
    own_elsewhere = collections.Counter(
        f"{proto.upper()} {rip}:{rport}" for (proto, _, _, rip, rport, _) in own_sockets
        if not (proto == "tcp" and rip == GATEWAY and rport in PROXY_SIDE_PORTS) and not ipaddress.ip_address(rip).is_loopback
    )
    if not owners.own:
        facts.append("ProxyFlow's own sockets: not checked, no test logged ProxyFlow's uid")
    elif own_elsewhere:
        problems.append(f"ProxyFlow's own sockets reached other places than its proxy: {dict(own_elsewhere.most_common(8))}")
        facts.append(f"ProxyFlow's own sockets (uid {sorted(owners.own)}): {len(own_sockets)} seen, some elsewhere than its proxy")
    else:
        facts.append(f"ProxyFlow's own sockets (uid {sorted(owners.own)}): {len(own_sockets)} seen, every one to its proxy or the gate's control port")
    views, view_problems = androids_views(logcat)
    problems += view_problems
    facts.append(
        f"Android's view of ProxyFlow's VPN after each connection (its network's capabilities): {len([v for v in views if v.startswith('every uid but')])} readable; "
        f"{dict(collections.Counter(views)) or 'none'}" + (f"; problems: {view_problems[:3]}" if view_problems else "")
    )
    # Traffic outside the VPN whose owner no sample named can only have come from an app Android lets leave the VPN,
    # and is reported as such, when Android's own view of this VPN left out ProxyFlow alone (and, where Android says,
    # let no app bypass it) and ProxyFlow's own sockets reached nothing but its proxy. Otherwise it fails the run.
    readable = [view for view in views if view.startswith("every uid but")]
    excusable = bool(readable) and len(readable) == len(views) and not view_problems and bool(owners.own) and not own_elsewhere
    refused_probes = [int(m.group(1)) for line in logcat if (m := re.search(r"ProxyFlowE2E\s*:\s*engine local_refused=(\d+)", line))]
    if refused_probes:
        facts.append(f"TCP to ProxyFlow's own addresses refused on the phone (Android's DNS-over-TLS probe, engine local_refused): {max(refused_probes)}")
    if not uplink_dns:
        facts.append(
            "the phone's uplink DNS server got no query after the lab's checks: this image's uplink does not use it "
            "(the captures still show every DNS query)"
        )

    # The gate: a connection forwarded to a TLS port opens with a TLS record.
    gate_mode, gate_plain, gate_tls, gate_empty, waiting = "", [], 0, 0, {}
    for line in after("gate.log"):
        m = re.search(r"mode forward 127\.0\.0\.1:(\d+)", line)
        if m:
            gate_mode = m.group(1)  # older gate logs name the upstream only on the mode line
            continue
        m = re.search(r"connection (\d+) late first=(.*)$", line)
        if m and m.group(1) in waiting:  # the first bytes of a connection that waited past the gate's 15 s
            port = waiting.pop(m.group(1))
            if m.group(2).startswith("16 03"):
                gate_tls += 1
            else:
                gate_plain.append(f"{port}: late {m.group(2)}")
            continue
        m = re.search(r"connection (\d+) from \S+ mode=forward (?:upstream=127\.0\.0\.1:(\d+) )?first=([0-9a-f]{2}(?: [0-9a-f]{2}){0,2}|-)(?: \((\w+)\))?", line)
        if not m:
            continue
        number, upstream, first, why = m.groups()
        port = int(upstream or gate_mode or 0)
        if port not in TLS_PORTS:
            continue
        if first == "16" or first.startswith("16 03"):  # a TLS record (the gate read one byte, or more)
            gate_tls += 1
        elif first == "-" and why == "timeout":
            waiting[number] = port
        elif first == "-":
            gate_empty += 1  # closed or reset before sending anything (a dial cancelled by a disconnect): no plaintext either
        else:
            gate_plain.append(line[-60:])
    gate_empty += len(waiting)  # waited and never sent anything
    if gate_plain:
        facts.append(f"gate: plaintext to a TLS front: {gate_plain[:3]}")
    else:
        facts.append(
            f"gate: {gate_tls} connections to a TLS front, every one opening with a TLS record"
            + (f" ({gate_empty} more closed before sending anything)" if gate_empty else "")
        )
    if gate_plain:
        problems.append(f"the app sent plaintext to a TLS proxy through the gate: {gate_plain[:3]}")
    if gate_tls == 0:
        problems.append("no connection went through the gate to a TLS front: the certificate-change test did not run, so nothing shows its retries stayed TLS")

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
    # Per interface where the device script recorded it: a phone capture spans the tests only if its own tcpdump
    # still ran at the end (another interface's surviving process says nothing about it).
    alive_at_end = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the end on:")), None)
    alive_at_start = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the start on:")), None)
    covering = 0
    checked: list[str] = []
    host_offset: float | None = None
    summary += [
        "| Capture | Packets | SOCKS5 targets (by kind) | HTTP CONNECT targets | TLS-port streams | To lab addresses | DNS for lab names |",
        "|---|---|---|---|---|---|---|",
    ]
    captures = [read_capture(path) for path in paths]
    # For the report: which captures saw each connection or datagram, by its own ends.
    seen_by: dict = collections.defaultdict(set)
    for capture in captures:
        for (_, src, dst, kind, port, sport) in capture.events:
            if sport is not None:
                seen_by[(kind, src, sport, dst, port)].add(os.path.basename(capture.path))
    reported_lookups: list[tuple[datetime.datetime, str]] = []  # lookups in the captures put down to an app Android lets leave the VPN
    for capture in captures:
        name = os.path.basename(capture.path)
        if capture.error:
            facts.append(f"capture {name}: unreadable ({capture.error})")
            continue
        kinds = collections.Counter(target.split(" ", 1)[0] for target in capture.socks_targets.elements())
        lab_dns = {n: c for n, c in capture.dns_names.items() if n in LAB_NAMES}
        lab_echo = {a: c for a, c in capture.echo_requests.items() if a in LAB_ADDRESSES}
        tls_streams = {f"{port} {kind}": count for (port, kind), count in sorted(capture.tls_port_streams.items())}
        facts.append(
            f"capture {name}: {capture.packets} packets; SOCKS5 CONNECT targets {dict(capture.socks_targets.most_common(8))}; "
            f"HTTP CONNECT targets {dict(capture.connect_targets.most_common(8))}; streams to TLS ports {tls_streams}; "
            f"packets to/from lab addresses {dict(capture.lab_packets) or 0}; SYNs to the LAN destination {capture.lan_syns}; "
            f"UDP to lab addresses {dict(capture.udp_to_lab) or 0}; echo requests to lab addresses {lab_echo or 0} "
            f"(to anywhere: {dict(collections.Counter(f'{a} -> {b}' for (_, a, b, kind, _, _) in capture.events if kind == 'ICMP').most_common(6)) or 0}); "
            f"IPv6 packets {capture.ipv6_packets}; "
            f"DNS queries for lab names {lab_dns or 0}; DNS names queried {len(capture.dns_names)}"
        )
        summary.append(
            f"| {name} | {capture.packets} | {dict(kinds)} | {sum(capture.connect_targets.values())} | {tls_streams} | "
            f"{sum(capture.lab_packets.values())} | {sum(lab_dns.values())} |"
        )
        partial = guest_capture_partial(name, guest_spans_tests, alive_at_end, alive_at_start)
        if not partial and capture.covers_uplink:
            covering += 1
        for count_name, count in (
            ("packets to or from a lab destination's address", sum(capture.lab_packets.values())),
            ("SYNs straight to the LAN destination 10.0.2.2:8082", capture.lan_syns),
            ("UDP datagrams to a lab address (the UDP sinks are 10.99.0.1:9999 and [fd99::1]:9999)", sum(capture.udp_to_lab.values())),
            ("echo requests (ping) to a lab address", sum(lab_echo.values())),
            ("DNS queries for lab destination names", sum(lab_dns.values())),
        ):
            if count:
                problems.append(f"capture {name}: {count} {count_name}")
        if capture.plaintext_on_tls:
            problems.append(f"capture {name}: plaintext opened a connection to a TLS proxy port: {capture.plaintext_on_tls[:3]}")
        if windows and not partial and capture.covers_uplink:
            phone = phone_of(capture, network, sockets)
            offset, how = capture_offset(capture, starts, phone)
            if offset is None:
                problems.append(f"capture {name}: could not be lined up with logcat ({how}), so its connected periods went unchecked")
            else:
                shift = datetime.timedelta(seconds=offset)
                if not name.startswith("guest-"):
                    host_offset = offset  # the emulator's own capture runs on the runner's clock, like the DNS log
                android_addresses = set().union(*(capture.dns_answers.get(n, set()) for n in ANDROID_NAMES))

                def classify(kind: str, src: str, sport: int | None, dst: str, port: int, local: datetime.datetime) -> tuple[str, list[int]]:
                    """'bypassing' (owned by apps Android lets leave a VPN), 'unidentified' (no owner sampled, see
                    excusable) or 'around' (fails the run), and the owners found."""
                    uids = sockets.owners(kind.lower(), src, sport, dst, port, local) if kind in ("TCP", "UDP") and sport is not None else []
                    if uids and all(owners.may_bypass(uid) for uid in uids):
                        return "bypassing", uids
                    return ("unidentified" if not uids and excusable else "around"), uids

                bypassing, unidentified, around = collections.Counter(), collections.Counter(), collections.Counter()
                where: list[str] = []
                connections = set()
                for event in capture.events:
                    when, src, dst, kind, port, sport = event
                    local = when and when - shift
                    if src not in phone or not inside(local, windows) or explained(dst, kind, port, android_addresses):
                        continue
                    if kind == "TCP":
                        if (src, sport, dst, port) in connections:
                            continue  # a retransmitted SYN: the same connection
                        connections.add((src, sport, dst, port))
                    key = f"{kind} [{dst}]:{port}" if ":" in dst else f"{kind} {dst}:{port}"
                    verdict, uids = classify(kind, src, sport, dst, port, local)
                    if verdict == "bypassing":
                        bypassing[f"{key} ({', '.join(owners.label(uid) for uid in uids)})"] += 1
                    elif verdict == "unidentified":
                        unidentified[key] += 1
                    else:
                        around[key] += 1
                        if len(where) < 4:
                            owner = ", ".join(owners.label(uid) for uid in uids) or f"owner not sampled, {sockets.near(local)} snapshots within 1 s"
                            seen = sorted(seen_by.get((kind, src, sport, dst, port), {name}))
                            origin = f"from {src}" + (f" port {sport}" if sport is not None else "") + f", hop limit {capture.hops.get(event, '?')}"
                            where.append(f"{key} at {local:%H:%M:%S.%f}"[:-3] + f" ({period_of(local, windows)}; {owner}; {origin}; seen by {', '.join(seen)})")
                names_inside = collections.Counter()
                lookups_bypassing, lookups_unidentified, lookups_around = collections.Counter(), collections.Counter(), collections.Counter()
                for (when, n, src, sport, dst, transport) in capture.dns_queries:
                    local = when and when - shift
                    if not inside(local, windows):
                        continue
                    names_inside[n] += 1
                    if n in ANDROID_NAMES or n == PROXY_NAME:
                        continue
                    verdict, uids = classify(transport, src, sport, dst, 53, local)
                    if verdict == "bypassing":
                        lookups_bypassing[f"{n} ({', '.join(owners.label(uid) for uid in uids)})"] += 1
                        reported_lookups.append((local, n))
                    elif verdict == "unidentified":
                        lookups_unidentified[n] += 1
                        reported_lookups.append((local, n))
                    else:
                        lookups_around[n] += 1
                checked.append(name)
                facts.append(
                    f"capture {name} while connected ({len(windows)} periods, {span:.0f} s; its clock {offset:+.2f} s from logcat's, {how}; "
                    f"phone {sorted(phone.addresses)}): new connections, datagrams or pings from the phone other than to its proxy, its network's "
                    f"resolver or Android's own checks: {dict(around.most_common(12)) or 'none'}{' — ' + '; '.join(where) if where else ''}; "
                    f"by apps Android lets leave a VPN (system uids, network bypass permissions): {dict(bypassing.most_common(8)) or 'none'}; "
                    f"with no owner sampled, so theirs (Android routes every other app into this VPN): {dict(unidentified.most_common(8)) or 'none'}; "
                    f"DNS queries: {dict(names_inside.most_common(12)) or 'none'}"
                    + (f"; lookups by apps Android lets leave a VPN: {dict(lookups_bypassing.most_common(8))}" if lookups_bypassing else "")
                    + (f"; lookups with no owner sampled: {dict(lookups_unidentified.most_common(8))}" if lookups_unidentified else "")
                )
                if around:
                    problems.append(f"capture {name}: traffic left the phone around the VPN while connected: {dict(around.most_common(12))}; {'; '.join(where)}")
                if lookups_around:
                    problems.append(f"capture {name}: the phone looked up names on its own network while connected: {dict(lookups_around.most_common(12))}")
        named = [t for t in capture.socks_targets if t.startswith("name proxyflow.test:") or t.startswith("name secure.proxyflow.test:")]
        if capture.covers_uplink and capture.socks_targets and not named:
            problems.append(f"capture {name}: no SOCKS5 request asked for a lab name: the names did not reach the proxy as names")
    if covering == 0:
        problems.append("no packet capture that ran through all the tests saw the app's connections to both proxies")
    if windows and not checked:
        problems.append("no capture could be checked for the periods ProxyFlow was connected")

    # The uplink DNS server's log, for the same periods: on the runner's clock, like the emulator's own capture.
    if not windows:
        facts.append("lookups on the uplink while connected: no connected period in logcat")
    else:
        if host_offset is not None:
            dns_shift, how = datetime.timedelta(seconds=host_offset), f"lined up through the emulator's capture ({host_offset:+.2f} s)"
        else:
            agree, how = clocks_agree(queried, starts)
            dns_shift = datetime.timedelta(0) if agree else None
        if dns_shift is None:
            facts.append(f"lookups on the uplink while connected: not lined up ({how}); the captures above cover those periods")
        else:
            own, android, matched, unexplained = 0, collections.Counter(), collections.Counter(), collections.Counter()
            for when, n, kind in queried:
                local = when - dns_shift
                if not inside(local, windows):
                    continue
                if n == PROXY_NAME:
                    own += 1
                elif n in ANDROID_NAMES:
                    android[f"{n} {kind}"] += 1
                elif any(looked == n and abs((t - local).total_seconds()) <= 2.5 for t, looked in reported_lookups):
                    matched[f"{n} {kind}"] += 1  # the same lookup a capture put down to an app Android lets leave the VPN
                else:
                    unexplained[f"{n} {kind}"] += 1
            facts.append(
                f"lookups on the uplink while connected ({len(windows)} periods, {span:.0f} s; {how}): {own} of the proxy's own name; "
                f"Android's network checks and time: {dict(android) or 'none'}; "
                + (f"by apps Android lets leave a VPN (from the captures): {dict(matched.most_common(8))}; " if matched else "")
                + f"any other: {dict(unexplained.most_common(12)) or 'none'}"
            )
            if unexplained:
                problems.append(f"the phone looked up names on its own network while connected, around the VPN: {dict(unexplained.most_common(12))}")
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
        annotate("error", f"M6 end-to-end evidence (API {args.api})", problems + facts)
        return 1
    annotate("notice", f"M6 end-to-end evidence (API {args.api})", facts)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception:  # an evidence check that cannot finish is a failed check, said where CI shows annotations
        import traceback

        api = next((sys.argv[i + 1] for i, a in enumerate(sys.argv[:-1]) if a == "--api"), "?")
        print(f"::error title=M6 end-to-end evidence (API {api})::the evidence script failed: {escape(traceback.format_exc())}")
        sys.exit(1)
