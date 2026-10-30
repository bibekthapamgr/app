"""Destination servers "on the internet" for the feasibility spike.

HTTP on :80 and :8081, HTTPS (web.test certificate) on :443, UDP echo on
:5000 — on both 10.30.0.2 and fd00:30::2. Every request is logged with the
source address the server saw, which is how the tests prove traffic arrived
from the proxy and never from the device.

GET /whoami          -> {"client_ip": ..., "server_port": ..., "tls": ...}
GET /nonce/<value>   -> same plus the nonce (end-to-end payload check)
GET /sleep/<secs>    -> holds the connection open (concurrency test)
GET /bytes/<n>       -> n bytes of payload (transfer test)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import ssl
import time


def log(_logfile, **kv):
    kv["ts"] = round(time.time(), 6)
    with open(_logfile, "a", encoding="utf-8") as f:
        f.write(json.dumps(kv, sort_keys=True) + "\n")


def make_handler(logfile: str, tls: bool):
    async def handle(r: asyncio.StreamReader, w: asyncio.StreamWriter):
        peer = w.get_extra_info("peername")
        local = w.get_extra_info("sockname")
        try:
            head = await asyncio.wait_for(r.readuntil(b"\r\n\r\n"), timeout=15)
            line = head.split(b"\r\n", 1)[0].decode("latin-1")
            parts = line.split(" ")
            path = parts[1] if len(parts) > 1 else "/"
            info = {"client_ip": peer[0], "client_port": peer[1], "server_port": local[1], "tls": tls, "path": path}
            body: bytes
            if path.startswith("/nonce/"):
                info["nonce"] = path[len("/nonce/"):]
            if path.startswith("/sleep/"):
                await asyncio.sleep(min(float(path[len("/sleep/"):]), 30))
            if path.startswith("/bytes/"):
                n = min(int(path[len("/bytes/"):]), 512 * 1024 * 1024)
                w.write(f"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {n}\r\nConnection: close\r\n\r\n".encode())
                chunk = bytes(range(256)) * 256
                sent = 0
                while sent < n:
                    piece = chunk[: min(len(chunk), n - sent)]
                    w.write(piece)
                    await w.drain()
                    sent += len(piece)
                log(logfile, event="request", **info, bytes=n)
                return
            body = (json.dumps(info) + "\n").encode()
            w.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                    + str(len(body)).encode() + b"\r\n\r\n" + body)
            await w.drain()
            log(logfile, event="request", **info)
        except Exception as e:  # noqa: BLE001
            log(logfile, event="error", client_ip=peer[0], error=type(e).__name__, tls=tls)
        finally:
            try:
                w.close()
            except Exception:
                pass

    return handle


class UDPEcho(asyncio.DatagramProtocol):
    def __init__(self, logfile):
        self.logfile = logfile

    def connection_made(self, transport):
        self.t = transport

    def datagram_received(self, data, addr):
        log(self.logfile, event="udp", client_ip=addr[0], bytes=len(data))
        self.t.sendto(data, addr)


async def main(a):
    ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ctx.load_cert_chain(a.cert, a.key)
    servers = []
    loop = asyncio.get_running_loop()
    for host in a.host:
        for port in (80, 8081):
            servers.append(await asyncio.start_server(make_handler(a.log, False), host, port, reuse_address=True))
        servers.append(await asyncio.start_server(make_handler(a.log, True), host, 443, ssl=ctx, reuse_address=True))
        await loop.create_datagram_endpoint(lambda: UDPEcho(a.log), local_addr=(host, 5000))
    log(a.log, event="listening", hosts=a.host)
    await asyncio.gather(*(s.serve_forever() for s in servers))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", action="append", required=True)
    ap.add_argument("--cert", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--log", required=True)
    asyncio.run(main(ap.parse_args()))
