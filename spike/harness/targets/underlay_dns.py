"""Minimal static DNS server playing the *underlying network's* resolver
(the Wi-Fi/cellular DNS on a phone). The engine uses it only to resolve the
proxy's own hostname, through a protected socket. Every query is logged so
the tests can prove app DNS never reaches it.

Answers A records from --record name=ipv4, NXDOMAIN otherwise.
"""
from __future__ import annotations

import argparse
import json
import socket
import struct
import time


def parse_qname(data: bytes, off: int):
    labels = []
    while True:
        ln = data[off]
        off += 1
        if ln == 0:
            break
        labels.append(data[off:off + ln].decode("ascii", "replace"))
        off += ln
    return ".".join(labels).lower(), off


def main(a):
    records = dict(r.split("=", 1) for r in a.record)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((a.listen, 53))
    while True:
        data, addr = s.recvfrom(4096)
        try:
            qid, flags, qd = struct.unpack("!HHH", data[:6])
            name, off = parse_qname(data, 12)
            qtype, qclass = struct.unpack("!HH", data[off:off + 4])
            question = data[12:off + 4]
            ip = records.get(name) if qtype == 1 else None
            rcode = 0 if (ip or name in records) else 3
            ancount = 1 if ip else 0
            resp = struct.pack("!HHHHHH", qid, 0x8180 | rcode, 1, ancount, 0, 0) + question
            if ip:
                resp += struct.pack("!HHHLH", 0xC00C, 1, 1, 30, 4) + socket.inet_aton(ip)
            s.sendto(resp, addr)
            with open(a.log, "a") as f:
                f.write(json.dumps({"ts": time.time(), "client": addr[0], "name": name, "qtype": qtype,
                                    "answer": ip, "rcode": rcode}) + "\n")
        except Exception as e:  # noqa: BLE001
            with open(a.log, "a") as f:
                f.write(json.dumps({"ts": time.time(), "error": type(e).__name__}) + "\n")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", required=True)
    ap.add_argument("--record", action="append", default=[])
    ap.add_argument("--log", required=True)
    main(ap.parse_args())
