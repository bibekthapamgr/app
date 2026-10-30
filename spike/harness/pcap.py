"""Packet-capture analysis via tshark (field extraction) for the spike tests."""
from __future__ import annotations

import ipaddress
import subprocess
from dataclasses import dataclass
from pathlib import Path

FIELDS = ["frame.number", "ip.src", "ip.dst", "ipv6.src", "ipv6.dst", "ip.proto", "ipv6.nxt",
          "tcp.srcport", "tcp.dstport", "udp.srcport", "udp.dstport", "icmp.type", "icmpv6.type",
          "tcp.flags.syn", "tcp.flags.ack", "tcp.flags.reset", "frame.len"]


@dataclass
class Pkt:
    n: int
    src: str
    dst: str
    proto: str  # tcp | udp | icmp | icmpv6 | other
    sport: int | None
    dport: int | None
    icmp_type: int | None
    syn: bool
    ack: bool
    rst: bool
    length: int

    @property
    def is_link_local_noise(self) -> bool:
        """IPv6 link-local / multicast housekeeping (ND, MLD) — not app traffic."""
        for a in (self.src, self.dst):
            try:
                ip = ipaddress.ip_address(a)
            except ValueError:
                return False
            if ip.version == 6 and (ip.is_link_local or ip.is_multicast):
                return True
        return False


def read(pcap: Path) -> list[Pkt]:
    if not Path(pcap).exists() or Path(pcap).stat().st_size == 0:
        return []
    args = ["tshark", "-r", str(pcap), "-T", "fields", "-E", "separator=|", "-E", "occurrence=f"]
    for f in FIELDS:
        args += ["-e", f]
    out = subprocess.run(args, capture_output=True, text=True, check=False).stdout
    pkts = []
    for line in out.splitlines():
        v = line.split("|")
        if len(v) < len(FIELDS):
            continue
        d = dict(zip(FIELDS, v))
        src = d["ip.src"] or d["ipv6.src"]
        dst = d["ip.dst"] or d["ipv6.dst"]
        if not src:
            continue  # ARP etc.
        # ICMP first: ICMP errors embed the offending IP/UDP header, so
        # tshark also fills udp.* / tcp.* fields for them.
        if d["icmp.type"]:
            proto = "icmp"
        elif d["icmpv6.type"]:
            proto = "icmpv6"
        elif d["tcp.srcport"]:
            proto = "tcp"
        elif d["udp.srcport"]:
            proto = "udp"
        else:
            proto = "other"
        num = lambda k: int(d[k]) if d[k] else None  # noqa: E731
        pkts.append(Pkt(
            n=int(d["frame.number"]), src=src, dst=dst, proto=proto,
            sport=None if proto.startswith("icmp") else (num("tcp.srcport") or num("udp.srcport")),
            dport=None if proto.startswith("icmp") else (num("tcp.dstport") or num("udp.dstport")),
            icmp_type=num("icmp.type") if d["icmp.type"] else num("icmpv6.type"),
            syn=d["tcp.flags.syn"] in ("1", "True"), ack=d["tcp.flags.ack"] in ("1", "True"),
            rst=d["tcp.flags.reset"] in ("1", "True"), length=int(d["frame.len"] or 0)))
    return pkts


def uplink_violations(pkts: list[Pkt], device_ips: set[str], allowed: set[tuple[str, int]],
                      allowed_udp: set[tuple[str, int]] = frozenset()) -> list[Pkt]:
    """Packets that left the device to anything other than an allowed proxy endpoint.

    allowed: {(proxy_ip, port)} for TCP; allowed_udp: {(ip, port)} e.g. the
    underlay resolver used only for the proxy's own hostname.
    """
    bad = []
    for p in pkts:
        if p.is_link_local_noise:
            continue
        if p.src in device_ips:
            peer, port = p.dst, p.dport
        elif p.dst in device_ips:
            peer, port = p.src, p.sport
        else:
            bad.append(p)  # neither side is the device: unexpected
            continue
        if p.proto == "tcp" and (peer, port) in allowed:
            continue
        if p.proto == "udp" and (peer, port) in allowed_udp:
            continue
        bad.append(p)
    return bad


def contains_bytes(pcap: Path, needle: bytes) -> bool:
    """True if the raw capture contains needle (plaintext exposure check)."""
    return Path(pcap).exists() and needle in Path(pcap).read_bytes()


def summary(pkts: list[Pkt]) -> dict:
    flows = {}
    for p in pkts:
        if p.is_link_local_noise:
            continue
        key = f"{p.proto} {p.src}:{p.sport or ''} -> {p.dst}:{p.dport or ''}"
        flows[key] = flows.get(key, 0) + 1
    return {"packets": len(pkts), "flows": dict(sorted(flows.items(), key=lambda kv: -kv[1])[:40])}
