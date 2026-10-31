"""Reviewer's ICMP probe: writes raw IPv4/IPv6 echo requests into a TUN via
AF_PACKET and reports every packet the TUN reader writes back, including
IPv6 fragments (Fragment header 44), which the harness/Go test do not parse."""
import json, select, socket, struct, sys, time

iface = sys.argv[1]
which = sys.argv[2].split(",") if len(sys.argv) > 2 else None
APP4, APP6 = "198.18.0.1", "fd00:66::1"
DST4, DST6 = "10.30.0.2", "2001:db8::1"


def csum(b, init=0):
    if len(b) % 2:
        b += b"\0"
    s = init + sum(struct.unpack("!%dH" % (len(b) // 2), b))
    while s >> 16:
        s = (s & 0xffff) + (s >> 16)
    return (~s) & 0xffff


def ip4(src, dst, proto, payload, ident=1, off8=0, mf=False):
    flags = (0x2000 if mf else 0) | off8
    h = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), ident, flags, 64, proto, 0,
                    socket.inet_aton(src), socket.inet_aton(dst))
    return h[:10] + struct.pack("!H", csum(h)) + h[12:] + payload


def icmp4_echo(payload, ident=0x4321, seq=1):
    m = struct.pack("!BBHHH", 8, 0, 0, ident, seq) + payload
    return m[:2] + struct.pack("!H", csum(m)) + m[4:]


def icmp6_echo(src, dst, payload, ident=0x1234, seq=1):
    m = struct.pack("!BBHHH", 128, 0, 0, ident, seq) + payload
    s, d = socket.inet_pton(socket.AF_INET6, src), socket.inet_pton(socket.AF_INET6, dst)
    c = csum(s + d + struct.pack("!I3xB", len(m), 58) + m)
    return m[:2] + struct.pack("!H", c) + m[4:]


def ip6(src, dst, nxt, upper):
    return (struct.pack("!IHBB", 6 << 28, len(upper), nxt, 64) + socket.inet_pton(socket.AF_INET6, src)
            + socket.inet_pton(socket.AF_INET6, dst) + upper)


def ext(nxt):  # 8-byte extension header with one PadN option (hop-by-hop or dest opts)
    return bytes([nxt, 0, 1, 4, 0, 0, 0, 0])


def frag6(nxt, off8, more, ident):
    return struct.pack("!BBHI", nxt, 0, (off8 << 3) | (1 if more else 0), ident)


def v6_fragmented(src, dst, icmp, first_len, ident):
    pkts, off = [], 0
    while off < len(icmp):
        chunk = icmp[off:off + first_len]
        more = off + len(chunk) < len(icmp)
        pkts.append(ip6(src, dst, 44, frag6(58, off // 8, more, ident) + chunk))
        off += len(chunk)
    return pkts


def v4_fragmented(src, dst, icmp, first_len, ident):
    pkts, off = [], 0
    while off < len(icmp):
        chunk = icmp[off:off + first_len]
        more = off + len(chunk) < len(icmp)
        pkts.append(ip4(src, dst, 1, chunk, ident=ident, off8=off // 8, mf=more))
        off += len(chunk)
    return pkts


big = bytes(range(256)) * 8  # 2048-byte payload
probes = {
    "v4_plain": [(0x0800, ip4(APP4, DST4, 1, icmp4_echo(b"pf-rv-v4")))],
    "v4_fragmented_2056B": [(0x0800, p) for p in v4_fragmented(APP4, DST4, icmp4_echo(big), 1480, 77)],
    "v6_plain": [(0x86DD, ip6(APP6, DST6, 58, icmp6_echo(APP6, DST6, b"pf-rv-v6")))],
    "v6_destopts": [(0x86DD, ip6(APP6, "2001:db8::2", 60, ext(58) + icmp6_echo(APP6, "2001:db8::2", b"pf-rv-do")))],
    "v6_hopbyhop": [(0x86DD, ip6(APP6, "2001:db8::3", 0, ext(58) + icmp6_echo(APP6, "2001:db8::3", b"pf-rv-hbh")))],
    "v6_atomic_fragment": [(0x86DD, ip6(APP6, "2001:db8::4", 44, frag6(58, 0, False, 99) + icmp6_echo(APP6, "2001:db8::4", b"pf-rv-af")))],
    "v6_small_2frags": [(0x86DD, p) for p in v6_fragmented(APP6, "2001:db8::5", icmp6_echo(APP6, "2001:db8::5", b"pf-rv-small-frag"), 8, 100)],
    # what `ping -6 -s 2048` does on a 1500-MTU TUN: the kernel sends two fragments
    "v6_large_fragmented_2056B": [(0x86DD, p) for p in v6_fragmented(APP6, "2001:db8::6", icmp6_echo(APP6, "2001:db8::6", big), 1448, 101)],
    "v6_jumbo_fragmented_12296B": [(0x86DD, p) for p in v6_fragmented(APP6, "2001:db8::7", icmp6_echo(APP6, "2001:db8::7", big * 6), 8952, 102)],
}
if which:
    probes = {k: v for k, v in probes.items() if k in which}

rx = socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x0003))
rx.bind((iface, 0x0003))
tx = {p: socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(p)) for p in (0x0800, 0x86DD)}
time.sleep(0.2)
sent = {}
for name, pkts in probes.items():
    for proto, pkt in pkts:
        tx[proto].sendto(pkt, (iface, proto))
    sent[name] = [len(p) for _, p in pkts]
    time.sleep(0.05)

got, deadline = [], time.time() + 2.5
while time.time() < deadline:
    if not select.select([rx], [], [], 0.2)[0]:
        continue
    data, addr = rx.recvfrom(65535)
    if addr[2] == 4:  # PACKET_OUTGOING: our own probes
        continue
    rec = {"len": len(data)}
    ver = data[0] >> 4
    if ver == 4:
        ihl = (data[0] & 0xf) * 4
        fo = struct.unpack("!H", data[6:8])[0]
        rec.update(v=4, src=socket.inet_ntoa(data[12:16]), dst=socket.inet_ntoa(data[16:20]), proto=data[9],
                   frag_off=(fo & 0x1fff) * 8, mf=bool(fo & 0x2000))
        if data[9] == 1 and (fo & 0x1fff) == 0:
            rec["icmp_type"] = data[ihl]
            rec["echo_reply"] = data[ihl] == 0
    elif ver == 6:
        rec.update(v=6, src=socket.inet_ntop(socket.AF_INET6, data[8:24]), dst=socket.inet_ntop(socket.AF_INET6, data[24:40]))
        off, nxt, chain = 40, data[6], []
        while nxt in (0, 43, 60, 44) and off + 8 <= len(data):
            chain.append(nxt)
            if nxt == 44:
                fo = struct.unpack("!H", data[off + 2:off + 4])[0]
                rec["frag_off"], rec["mf"] = (fo >> 3) * 8, bool(fo & 1)
                nxt2 = data[off]
                off += 8
                if rec["frag_off"] != 0:
                    nxt = None
                    break
                nxt = nxt2
            else:
                nxt, off = data[off], off + (data[off + 1] + 1) * 8
        rec["ext_chain"] = chain
        rec["next"] = nxt
        if nxt == 58 and len(data) > off:
            rec["icmp6_type"] = data[off]
            rec["echo_reply"] = data[off] == 129
            if data[off] == 129:
                rec["echo_id"] = struct.unpack("!H", data[off + 4:off + 6])[0]
    got.append(rec)

print(json.dumps({"sent": sent, "received": got,
                  "echo_replies": [r for r in got if r.get("echo_reply")]}, indent=1))
