#!/usr/bin/env python3
"""The live proxy check's uplink evidence (scripts/live_proxy_check.sh): what left the phone while ProxyFlow was
connected to the owner's own proxy, read from the emulator's own capture of its uplink (`-tcpdump`, Ethernet).

Between the moment Connected showed and the moment Disconnect was tapped (host-clock seconds, as the script recorded
them), every IPv4 packet the phone sent must have gone to the proxy's address and port over TCP: a packet to any other
address is traffic around the proxy — a leak — and fails the check. Outside that window the phone talks to the
internet directly (Android's own connectivity checks, Google's services), which is as it should be and is only
counted. Broadcast, multicast, ARP, IPv6 link-local chatter and the interface's own DHCP (a raw socket below any VPN) are not
the phone's traffic to anyone and are ignored. DNS: while connected, nothing may leave for port 53 either — ProxyFlow answers the
phone's lookups itself and the proxy resolves names (ADR-0011) — so a query to any resolver is a leak like any other.

Usage: live_proxy_evidence.py <uplink.pcap> <proxy address> <proxy port> <connected-at> <disconnected-at> [phone address]
Exit 0 when nothing left the phone for anywhere but the proxy during the window; 1 otherwise; the facts on stdout.
The proxy's address appears in the output (it is the owner's; the username and password never reach this script).
"""
from __future__ import annotations

import ipaddress
import struct
import sys
from collections import Counter


def packets(path: str):
    with open(path, "rb") as f:
        head = f.read(24)
        if len(head) < 24:
            return
        magic = struct.unpack("<I", head[:4])[0]
        if magic == 0xA1B2C3D4:
            endian, nanos = "<", False
        elif magic == 0xD4C3B2A1:
            endian, nanos = ">", False
        elif magic == 0xA1B23C4D:
            endian, nanos = "<", True
        elif magic == 0x4D3CB2A1:
            endian, nanos = ">", True
        else:
            raise SystemExit(f"{path}: not a pcap file (magic {magic:#x})")
        linktype = struct.unpack(endian + "I", head[20:24])[0]
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                return
            sec, frac, incl, _orig = struct.unpack(endian + "IIII", rec)
            data = f.read(incl)
            if len(data) < incl:
                return
            yield sec + frac / (1e9 if nanos else 1e6), linktype, data


def ipv4_of(linktype: int, data: bytes) -> bytes | None:
    if linktype == 1:  # Ethernet
        if len(data) < 14 or data[12:14] != b"\x08\x00":
            return None
        return data[14:]
    if linktype == 101:  # raw IP
        return data if data and data[0] >> 4 == 4 else None
    if linktype == 113:  # Linux cooked
        if len(data) < 16 or data[14:16] != b"\x08\x00":
            return None
        return data[16:]
    return None


def main(argv: list[str]) -> int:
    if len(argv) < 5:
        print(__doc__)
        return 2
    path, proxy, port = argv[0], argv[1], int(argv[2])
    start, end = float(argv[3]), float(argv[4])
    phone = argv[5] if len(argv) > 5 else None
    proxy_ip = ipaddress.ip_address(proxy)
    inside: Counter[tuple[str, str, int]] = Counter()
    outside: Counter[tuple[str, str, int]] = Counter()
    proxy_syns_inside = 0
    first = last = None
    total = 0
    dhcp = 0
    for stamp, linktype, data in packets(path):
        total += 1
        ip = ipv4_of(linktype, data)
        if ip is None or len(ip) < 20:
            continue
        ihl = (ip[0] & 0x0F) * 4
        proto = ip[9]
        src = str(ipaddress.ip_address(ip[12:16]))
        dst_addr = ipaddress.ip_address(ip[16:20])
        dst = str(dst_addr)
        if phone is None:
            # The phone is the sender of the first packet to the proxy's address (its SYN), or, failing that, the
            # emulator's usual 10.0.2.15.
            if dst_addr == proxy_ip:
                phone = src
            else:
                continue
        if src != phone:
            continue
        if dst_addr.is_multicast or dst == "255.255.255.255" or dst_addr.is_link_local:
            continue
        dport = -1
        if proto in (6, 17) and len(ip) >= ihl + 4:
            dport = struct.unpack("!H", ip[ihl + 2 : ihl + 4])[0]
        if proto == 17 and dport in (67, 68):
            dhcp += 1  # the interface's own lease renewal (a raw socket on eth0, below any VPN): not a connection to anyone
            continue
        name = {6: "tcp", 17: "udp", 1: "icmp"}.get(proto, f"proto{proto}")
        key = (name, dst, dport)
        if start <= stamp <= end:
            inside[key] += 1
            first = stamp if first is None else first
            last = stamp
            if proto == 6 and dst_addr == proxy_ip and dport == port and len(ip) >= ihl + 14 and ip[ihl + 13] & 0x12 == 0x02:
                proxy_syns_inside += 1
        else:
            outside[key] += 1
    if phone is None:
        print(f"no IPv4 packet in {path} ({total} records) went to {proxy}; the phone never reached the proxy")
        return 1
    leaks = {k: n for k, n in inside.items() if not (k[0] == "tcp" and k[1] == proxy and k[2] == port)}
    to_proxy = sum(n for k, n in inside.items() if k[0] == "tcp" and k[1] == proxy and k[2] == port)
    print(f"{path}: {total} records; the phone is {phone}; window {start:.0f}–{end:.0f} ({end - start:.0f} s)")
    print(f"while connected: {to_proxy} packets to the proxy {proxy}:{port} over TCP ({proxy_syns_inside} new connections)"
          + (f", from {first - start:.1f} s to {last - start:.1f} s into the window" if first is not None else ""))
    print(f"outside the window (before Connected, after Disconnect): {sum(outside.values())} packets to "
          f"{len({k[1] for k in outside})} address(es) — the phone's own direct traffic, as expected when ProxyFlow is off"
          + (f"; {dhcp} DHCP packet(s) of the interface itself ignored" if dhcp else ""))
    if leaks:
        print("LEAK: while connected, the phone sent packets around the proxy:")
        for (name, dst, dport), n in sorted(leaks.items(), key=lambda kv: -kv[1]):
            print(f"  {n:6d}  {name} to {dst}" + (f":{dport}" if dport >= 0 else ""))
        return 1
    print("nothing left the phone for anywhere but the proxy while connected: no leak")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
