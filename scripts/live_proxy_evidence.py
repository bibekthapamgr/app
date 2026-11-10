#!/usr/bin/env python3
"""The live proxy check's uplink evidence (scripts/live_proxy_check.sh): what left the phone while ProxyFlow was
connected to the owner's own proxy, read from the emulator's own capture of its uplink (`-tcpdump`, Ethernet), and
who sent it, from the phone's own socket table sampled meanwhile (the same sampler and owner rules as the lab's
scripts/e2e_device.sh and scripts/m6_evidence.py).

Between the moment Connected showed and the moment Disconnect was tapped (host-clock seconds, as the script recorded
them), every IPv4 packet the phone sent is expected to have gone to the proxy's address and port over TCP. A packet to
anywhere else is sorted by its sender:
  - Android's own: a socket whose owner Android lets leave a VPN — a system uid, or an app granted a network-bypass
    permission, as Google Play services is on the Android 15 and 16 images (docs/m6/routing-and-limitations.md §4:
    "Privileged system apps can bypass the VPN"; ProxyFlow cannot stop it). Reported, not a failure. A DNS query to the
    phone's own resolver for a name of Google's or Android's is counted with these (the system resolver sends it for
    such an app; its owner in the table is the resolver's), as is an ICMP error the phone's stack answers an inbound
    packet with (destination unreachable, time exceeded).
  - A leak: a packet from any other owner — an ordinary app, the shell, ProxyFlow's own uid — or a connection, datagram
    or lookup with no owner sampled, or a DNS query for any other name. Fails the check.
Outside the window the phone talks to the internet directly (Android's own connectivity checks, Google's services),
which is as it should be and is only counted. Broadcast, multicast, ARP, IPv6 link-local chatter and the interface's
own DHCP (a raw socket below any VPN) are not the phone's traffic to anyone and are ignored.

Usage: live_proxy_evidence.py <uplink.pcap> <proxy address> <proxy port> <connected-at> <disconnected-at>
                              [--out DIR] [--phone ADDRESS]
  --out: the directory with phone-connecting.txt (the socket samples), packages-uids.txt and
         packages-network-permissions.txt, as the check script pulled them; without it no owner can be named and every
         packet around the proxy is a leak.
Exit 0 when nothing but the proxy's traffic and Android's own left the phone during the window; 1 otherwise; the facts
on stdout. The proxy's address appears in the output (the job masks it); the username and password never reach here.
"""
from __future__ import annotations

import argparse
import datetime
import ipaddress
import os
import struct
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m5_evidence import dns_name  # noqa: E402
from m6_evidence import Owners, network_grants, package_names, socket_samples  # noqa: E402

# Names whose lookups while connected are Android's and Google's own services' (the privileged apps above): the resolver
# asks the phone's usual DNS for them around the VPN. Any other name asked around the proxy is a leak.
SYSTEM_NAME_SUFFIXES = (".google.com", ".gstatic.com", ".googleapis.com", ".android.com", ".googleusercontent.com",
                        ".gvt1.com", ".gvt2.com", ".1e100.net", ".googlezip.net", ".app-measurement.com",
                        ".google", ".ggpht.com", ".youtube.com", ".googlevideo.com")


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


def system_name(name: str) -> bool:
    return name.endswith(SYSTEM_NAME_SUFFIXES) or name in ("google.com", "android.com", "gstatic.com")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pcap")
    parser.add_argument("proxy")
    parser.add_argument("port", type=int)
    parser.add_argument("start", type=float)
    parser.add_argument("end", type=float)
    parser.add_argument("--out", help="the directory with the socket samples and the package tables")
    parser.add_argument("--phone", help="the phone's address on the uplink (found from its first packet to the proxy otherwise)")
    args = parser.parse_args(argv)
    path, proxy, port, start, end, phone = args.pcap, args.proxy, args.port, args.start, args.end, args.phone
    proxy_ip = ipaddress.ip_address(proxy)

    owners = None
    if args.out:
        sockets = socket_samples(args.out)
        owners = Owners(package_names(args.out), network_grants(args.out), set())
        print(f"the phone's socket table: {sockets.coverage()}; {len(owners.names)} packages with uids, "
              f"{sum(1 for g in owners.grants.values() if g)} uids with network permissions read")

    inside: dict[tuple[str, str, int], list] = defaultdict(list)  # (proto, dst, dport) -> [(stamp, sport, detail)]
    outside_count = 0
    outside_addresses: set[str] = set()
    proxy_packets = proxy_syns = 0
    first = last = None
    total = dhcp = 0
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
            if dst_addr == proxy_ip:
                phone = src
            else:
                continue
        if src != phone:
            continue
        if dst_addr.is_multicast or dst == "255.255.255.255" or dst_addr.is_link_local:
            continue
        l4 = ip[ihl:]
        sport = dport = -1
        detail = ""
        if proto in (6, 17) and len(l4) >= 4:
            sport, dport = struct.unpack("!HH", l4[:4])
        if proto == 17 and dport in (67, 68):
            dhcp += 1  # the interface's own lease renewal (a raw socket on eth0, below any VPN): not a connection to anyone
            continue
        if proto == 6 and len(l4) >= 14:
            flags = l4[13]
            detail = "syn" if flags & 0x12 == 0x02 else ("fin" if flags & 0x01 else ("rst" if flags & 0x04 else "data"))
        elif proto == 17 and dport == 53 and len(l4) >= 8:
            detail = "query " + (dns_name(l4[8:]) or "(unreadable)")
        elif proto == 1 and len(l4) >= 2:
            detail = {0: "echo reply", 3: "destination unreachable", 8: "echo request", 11: "time exceeded"}.get(l4[0], f"type {l4[0]}")
        name = {6: "tcp", 17: "udp", 1: "icmp"}.get(proto, f"proto{proto}")
        if start <= stamp <= end:
            first = stamp if first is None else first
            last = stamp
            if proto == 6 and dst_addr == proxy_ip and dport == port:
                proxy_packets += 1
                proxy_syns += detail == "syn"
            else:
                inside[(name, dst, dport)].append((stamp, sport, detail))
        else:
            outside_count += 1
            outside_addresses.add(dst)
    if phone is None:
        print(f"no IPv4 packet in {path} ({total} records) went to {proxy}; the phone never reached the proxy")
        return 1

    print(f"{path}: {total} records; the phone is {phone}; window {start:.0f}–{end:.0f} ({end - start:.0f} s)")
    print(f"while connected: {proxy_packets} packets to the proxy {proxy}:{port} over TCP ({proxy_syns} new connections)"
          + (f", from {first - start:.1f} s to {last - start:.1f} s into the window" if first is not None else ""))
    print(f"outside the window (before Connected, after Disconnect): {outside_count} packets to {len(outside_addresses)} "
          f"address(es) — the phone's own direct traffic, as expected when ProxyFlow is off"
          + (f"; {dhcp} DHCP packet(s) of the interface itself ignored" if dhcp else ""))
    if not inside:
        print("nothing left the phone for anywhere but the proxy while connected: no leak")
        return 0

    androids: list[str] = []
    leaks: list[str] = []
    for (name, dst, dport), items in sorted(inside.items(), key=lambda kv: -len(kv[1])):
        stamps = [s for s, _, _ in items]
        kinds = sorted({d for _, _, d in items if d})
        where = f"{name} to {dst}" + (f":{dport}" if dport >= 0 else "")
        when = f"{min(stamps) - start:.1f}–{max(stamps) - start:.1f} s into the window"
        line = f"{len(items):4d}  {where}  [{', '.join(kinds) or '-'}]  {when}"
        if name == "icmp":
            if all(d in ("destination unreachable", "time exceeded") for _, _, d in items):
                androids.append(line + "  — the phone's stack answering inbound packets, no connection of anyone's")
            else:
                leaks.append(line + "  — ICMP the phone sent on its own (ping is refused inside the VPN)")
            continue
        if name == "udp" and dport == 53:
            names_asked = sorted({d[6:] for _, _, d in items if d.startswith("query ")})
            if names_asked and all(system_name(n) for n in names_asked):
                androids.append(line + "  — the resolver asking the phone's usual DNS for Android's and Google's own services (documented: routing-and-limitations §4)")
            else:
                leaks.append(line + "  — a lookup around the proxy for a name that is not Android's own")
            continue
        uids: set[int] = set()
        if owners is not None:
            for stamp, sport, _ in items:
                when_dt = datetime.datetime.fromtimestamp(stamp, datetime.timezone.utc).replace(tzinfo=None)
                uids.update(sockets.owners(name, phone, sport, dst, dport, when_dt))
        if uids and all(owners.may_bypass(uid) for uid in uids):
            androids.append(line + "  — owner " + "; ".join(owners.label(u) for u in sorted(uids))
                            + ": Android lets it leave the VPN (documented: routing-and-limitations §4)")
        elif uids:
            leaks.append(line + "  — owner " + "; ".join(owners.label(u) for u in sorted(uids)) + ": not allowed around the VPN")
        else:
            leaks.append(line + "  — no owner sampled" + ("" if owners is not None else " (no socket samples given)"))
    if androids:
        print("while connected, around the proxy, Android's own (not ProxyFlow's to stop; reported, not a failure):")
        for line in androids:
            print("  " + line)
    if leaks:
        print("LEAK: while connected, the phone sent packets around the proxy that are not Android's own:")
        for line in leaks:
            print("  " + line)
        return 1
    print("nothing of any app's left the phone for anywhere but the proxy while connected: no leak"
          + (f" ({len(androids)} flow(s) of Android's own privileged services around it, listed above)" if androids else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
