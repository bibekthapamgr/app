"""'Apps on the phone': client actions executed inside the pf-dev namespace."""
from __future__ import annotations

import json
import os
import subprocess
import time
from dataclasses import dataclass

from lab import DEV


@dataclass
class CurlResult:
    rc: int
    code: int
    body: str
    seconds: float
    stderr: str

    def json(self):
        return json.loads(self.body)


# Apps on the "phone" must not inherit the host's proxy settings (HTTPS_PROXY
# etc.): everything they send has to go through the tunnel.
CLEAN_ENV = {k: v for k, v in os.environ.items() if "proxy" not in k.lower()}


def run(cmd: list[str], timeout=60, input_text=None):
    return subprocess.run(["ip", "netns", "exec", DEV, *cmd], capture_output=True, text=True,
                          timeout=timeout, input=input_text, env=CLEAN_ENV)


def curl(url: str, *extra: str, max_time: float = 8) -> CurlResult:
    t0 = time.time()
    r = run(["curl", "-sS", "-g", "--max-time", str(max_time), "-w", "\n%{http_code}", *extra, url],
            timeout=max_time + 10)
    out = r.stdout.rsplit("\n", 1)
    body, code = (out[0], out[1]) if len(out) == 2 else ("", "000")
    return CurlResult(rc=r.returncode, code=int(code or 0), body=body, seconds=round(time.time() - t0, 3),
                      stderr=r.stderr.strip())


def dig(*args: str) -> str:
    return run(["dig", "+time=2", "+tries=1", *args], timeout=15).stdout


def ping(*args: str) -> subprocess.CompletedProcess:
    return run(["ping", "-n", *args], timeout=30)


# Raw ICMP echo requests written straight into the TUN (AF_PACKET), so the
# IPv6 path is exercised even on a kernel booted with ipv6.disable=1. Small and
# large (fragmented) pings, with and without IPv6 extension headers. Reports
# every echo reply, and every fragment of an ICMP packet, that the TUN reader
# (the engine) writes back: ProxyFlow must never send either.
ICMP_INJECT = r"""
import json, select, socket, struct, sys, time
iface, dst4, dst6 = sys.argv[1], sys.argv[2], sys.argv[3]
S4, S6 = "198.18.0.1", "fd00:66::1"
def csum(b, init=0):
    if len(b) % 2: b += b"\0"
    s = init + sum(struct.unpack("!%dH" % (len(b) // 2), b))
    while s >> 16: s = (s & 0xffff) + (s >> 16)
    return (~s) & 0xffff
def v4(src, dst, size=0, ident=1):
    icmp = struct.pack("!BBHHH", 8, 0, 0, 0x4321, 1) + b"pf-e09" + bytes(size)
    icmp = icmp[:2] + struct.pack("!H", csum(icmp)) + icmp[4:]
    out = []
    for off in range(0, len(icmp), 1480):
        end = min(off + 1480, len(icmp))
        h = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + end - off, ident, off // 8 | (0x2000 if end < len(icmp) else 0),
                        64, 1, 0, socket.inet_aton(src), socket.inet_aton(dst))
        out.append(h[:10] + struct.pack("!H", csum(h)) + h[12:] + icmp[off:end])
    return out
def v6(src, dst, ext=False, size=0, frag=False, ident=1):
    s, d = socket.inet_pton(socket.AF_INET6, src), socket.inet_pton(socket.AF_INET6, dst)
    icmp = struct.pack("!BBHHH", 128, 0, 0, 0x1234, 1) + b"pf-e09" + bytes(size)
    icmp = icmp[:2] + struct.pack("!H", csum(s + d + struct.pack("!I3xB", len(icmp), 58) + icmp)) + icmp[4:]
    part, first = (bytes([58, 0, 1, 4, 0, 0, 0, 0]) + icmp, 60) if ext else (icmp, 58)
    hdr = lambda nxt, n: struct.pack("!IHBB", 6 << 28, n, nxt, 64) + s + d
    if not frag:
        return [hdr(first, len(part)) + part]
    out = []
    for off in range(0, len(part), 1448):
        end = min(off + 1448, len(part))
        fh = struct.pack("!BBHI", first, 0, (off // 8) << 3 | (1 if end < len(part) else 0), ident)
        out.append(hdr(44, 8 + end - off) + fh + part[off:end])
    return out
def answered(data):
    if not data: return None
    if data[0] >> 4 == 4 and len(data) >= 20 and data[9] == 1:
        ihl = (data[0] & 0xf) * 4
        if struct.unpack("!H", data[6:8])[0] & 0x3fff: return "ipv4 fragment"
        return "ipv4" if len(data) > ihl and data[ihl] == 0 else None
    if data[0] >> 4 == 6 and len(data) >= 40:
        off, nxt, fragmented = 40, data[6], False
        while nxt in (0, 43, 44, 60) and len(data) >= off + 8:
            if nxt == 44:
                fragmented, later = True, struct.unpack("!H", data[off + 2:off + 4])[0] >> 3
                nxt, off = data[off], off + 8
                if later: break
                continue
            nxt, off = data[off], off + (data[off + 1] + 1) * 8
        if nxt == 58 and fragmented: return "ipv6 fragment"
        if nxt == 58 and len(data) > off and data[off] == 129: return "ipv6"
    return None
rx = socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x0003)); rx.bind((iface, 0x0003))
tx = {0x0800: socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x0800)),
      0x86DD: socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x86DD))}
probes = {"ipv4": (0x0800, v4(S4, dst4)),
          "ipv4_2kb_fragmented": (0x0800, v4(S4, dst4, size=2042, ident=0x4402)),
          "ipv6": (0x86DD, v6(S6, dst6)),
          "ipv6_ext_header": (0x86DD, v6(S6, dst6, ext=True)),
          "ipv6_atomic_fragment": (0x86DD, v6(S6, dst6, frag=True, ident=0x103)),
          "ipv6_2kb_fragmented": (0x86DD, v6(S6, dst6, size=2042, frag=True, ident=0x104)),
          "ipv6_2kb_fragmented_after_ext_header": (0x86DD, v6(S6, dst6, ext=True, size=2042, frag=True, ident=0x105))}
for name, (proto, pkts) in probes.items():
    for pkt in pkts:
        tx[proto].sendto(pkt, (iface, proto))
replies, deadline = [], time.time() + 2.0
while time.time() < deadline:
    if not select.select([rx], [], [], 0.2)[0]: continue
    data, addr = rx.recvfrom(65535)
    if addr[2] == 4: continue  # our own transmissions
    a = answered(data)
    if a: replies.append(a)
print(json.dumps({"sent": {name: len(pkts) for name, (_, pkts) in probes.items()}, "echo_replies": replies}))
"""


def icmp_inject(iface: str, dst4: str, dst6: str) -> dict:
    r = run(["python3", "-c", ICMP_INJECT, iface, dst4, dst6], timeout=20)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return {"error": "inject failed", "stderr": r.stderr[-500:]}


UDP_PROBE = r"""
import socket, sys, json
host, port = sys.argv[1], int(sys.argv[2])
fam = socket.AF_INET6 if ':' in host else socket.AF_INET
s = socket.socket(fam, socket.SOCK_DGRAM)
s.settimeout(2.0)
s.connect((host, port))
res = {"sent": 0}
try:
    for i in range(3):
        s.send(b"spike-udp-probe-%d" % i); res["sent"] += 1
        try:
            data = s.recv(2048); res["reply"] = data.decode(errors="replace"); break
        except ConnectionRefusedError:
            res["error"] = "ECONNREFUSED (ICMP port unreachable from the tunnel)"; break
        except socket.timeout:
            res["error"] = "timeout"
except ConnectionRefusedError:
    res["error"] = "ECONNREFUSED (ICMP port unreachable from the tunnel)"
print(json.dumps(res))
"""


def udp_probe(host: str, port: int) -> dict:
    r = run(["python3", "-c", UDP_PROBE, host, str(port)], timeout=20)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return {"error": "probe failed", "stderr": r.stderr[-500:]}


CONCURRENCY = r"""
import asyncio, socket, sys, json, time
n, path = int(sys.argv[1]), sys.argv[2]
ip = socket.getaddrinfo("web.test", 80, socket.AF_INET)[0][4][0]
async def one(i):
    # Returns (result, time the app saw its connection end).
    try:
        r, w = await asyncio.wait_for(asyncio.open_connection(ip, 80), 15)
        w.write(f"GET {path} HTTP/1.1\r\nHost: web.test\r\nConnection: close\r\n\r\n".encode())
        await w.drain()
        data = await asyncio.wait_for(r.read(), 30)
        t = time.time()
        w.close()
        return data.startswith(b"HTTP/1.1 200"), t
    except Exception as e:
        return repr(e), time.time()
async def main():
    t0 = time.time()
    res = await asyncio.gather(*(one(i) for i in range(n)))
    ok = sum(1 for x, _ in res if x is True)
    errs = [x for x, _ in res if x is not True][:5]
    ends = [t for _, t in res]
    print(json.dumps({"requested": n, "ok": ok, "errors": errs, "fake_ip": ip, "seconds": round(time.time()-t0, 2),
                      "first_end": min(ends), "last_end": max(ends)}))
asyncio.run(main())
"""


def concurrent_requests(n: int, path: str) -> dict:
    r = run(["python3", "-c", CONCURRENCY, str(n), path], timeout=120)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return {"error": r.stderr[-800:]}


def iperf(*args: str) -> dict:
    r = run(["iperf3", "-J", *args], timeout=90)
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return {"error": r.stdout[-500:] + r.stderr[-500:]}
