"""Instrumented SOCKS5 / HTTP CONNECT proxy for the ProxyFlow feasibility spike.

This is the "controlled proxy": an independent implementation of both
protocols that records exactly what the engine asked for (hostname vs IP,
auth outcome, result) as JSON lines. It never logs passwords or payload.

Usage:
  spike_proxy.py socks5 --listen 10.20.0.2:1080 [--listen [fd00:20::2]:1080] --log x.jsonl [--auth USER]
  spike_proxy.py http   --listen 10.20.0.2:3128 --log y.jsonl [--auth USER] [--deny-port 8081]
The password for --auth is read from SPIKE_PROXY_PASSWORD.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import hmac
import ipaddress
import json
import os
import socket
import time

LOG = None
ARGS = None


def log(**kv):
    kv["ts"] = round(time.time(), 6)
    kv["proxy"] = ARGS.kind
    line = json.dumps(kv, sort_keys=True)
    with open(ARGS.log, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def split_listen(s: str):
    if s.startswith("["):
        host, port = s[1:].split("]:")
    else:
        host, port = s.rsplit(":", 1)
    return host, int(port)


async def pipe(r: asyncio.StreamReader, w: asyncio.StreamWriter, counter: list):
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            counter[0] += len(data)
            w.write(data)
            await w.drain()
    except (ConnectionError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        try:
            if w.can_write_eof():
                w.write_eof()
        except (OSError, RuntimeError):
            pass


async def relay(cr, cw, ur, uw):
    up, down = [0], [0]
    await asyncio.gather(pipe(cr, uw, up), pipe(ur, cw, down))
    for w in (uw, cw):
        try:
            w.close()
        except Exception:
            pass
    return up[0], down[0]


async def open_upstream(host: str, port: int):
    """Connect to the destination; returns (reader, writer) or raises with a category."""
    return await asyncio.wait_for(asyncio.open_connection(host, port), timeout=4)


def categorize(exc: BaseException) -> str:
    if isinstance(exc, ConnectionRefusedError):
        return "refused"
    if isinstance(exc, socket.gaierror):
        return "dns_failed"
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return "timeout"
    return "unreachable"


# ----------------------------------------------------------------- SOCKS5 ----

SOCKS_REP = {"refused": 0x05, "dns_failed": 0x04, "timeout": 0x04, "unreachable": 0x03}


async def socks5_session(cr: asyncio.StreamReader, cw: asyncio.StreamWriter):
    peer = cw.get_extra_info("peername")
    client = f"{peer[0]}:{peer[1]}"
    rec = {"event": "session", "client": client}
    try:
        ver, n = await cr.readexactly(2)
        if ver != 5:
            rec["result"] = "bad_version"
            return
        methods = await cr.readexactly(n)
        rec["methods_offered"] = list(methods)
        if ARGS.auth:
            if 2 not in methods:
                cw.write(b"\x05\xff")
                rec["auth"] = "no_acceptable_method"
                return
            cw.write(b"\x05\x02")
            await cw.drain()
            av = await cr.readexactly(1)
            ulen = (await cr.readexactly(1))[0]
            user = await cr.readexactly(ulen)
            plen = (await cr.readexactly(1))[0]
            pw = await cr.readexactly(plen)
            ok = hmac.compare_digest(user, ARGS.auth.encode()) and hmac.compare_digest(pw, PASSWORD.encode())
            cw.write(b"\x01\x00" if ok else b"\x01\x01")
            await cw.drain()
            rec["auth"] = "ok" if ok else "failed"
            rec["auth_version"] = av[0]
            if not ok:
                return
        else:
            if 0 not in methods:
                cw.write(b"\x05\xff")
                rec["auth"] = "no_acceptable_method"
                return
            cw.write(b"\x05\x00")
            rec["auth"] = "none"
        _, cmd, _, atyp = await cr.readexactly(4)
        if atyp == 1:
            host = str(ipaddress.IPv4Address(await cr.readexactly(4)))
            rec["atyp"] = "ipv4"
        elif atyp == 3:
            ln = (await cr.readexactly(1))[0]
            host = (await cr.readexactly(ln)).decode("ascii", "replace")
            rec["atyp"] = "domain"
        elif atyp == 4:
            host = str(ipaddress.IPv6Address(await cr.readexactly(16)))
            rec["atyp"] = "ipv6"
        else:
            rec["result"] = "bad_atyp"
            return
        port = int.from_bytes(await cr.readexactly(2), "big")
        rec["target"] = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
        if cmd != 1:
            cw.write(b"\x05\x07\x00\x01" + b"\x00" * 6)
            rec["result"] = f"cmd_{cmd}_unsupported"
            return
        if port in ARGS.deny_port:
            cw.write(b"\x05\x02\x00\x01" + b"\x00" * 6)
            rec["result"] = "not_allowed"
            return
        try:
            ur, uw = await open_upstream(host, port)
        except BaseException as e:  # noqa: BLE001
            cat = categorize(e)
            cw.write(bytes([5, SOCKS_REP[cat], 0, 1]) + b"\x00" * 6)
            rec["result"] = cat
            return
        local = uw.get_extra_info("sockname")
        rec["upstream_src"] = local[0]
        cw.write(b"\x05\x00\x00\x01" + b"\x00" * 6)
        await cw.drain()
        rec["result"] = "ok"
        log(**{**rec, "event": "connected"})
        up, down = await relay(cr, cw, ur, uw)
        rec["bytes_up"], rec["bytes_down"] = up, down
    except (asyncio.IncompleteReadError, ConnectionError, OSError) as e:
        rec.setdefault("result", f"io_error:{type(e).__name__}")
    finally:
        log(**rec)
        try:
            cw.close()
        except Exception:
            pass


# ------------------------------------------------------------ HTTP CONNECT ---

HTTP_STATUS = {"refused": 502, "dns_failed": 502, "timeout": 504, "unreachable": 502}


async def http_session(cr: asyncio.StreamReader, cw: asyncio.StreamWriter):
    peer = cw.get_extra_info("peername")
    rec = {"event": "session", "client": f"{peer[0]}:{peer[1]}"}

    def respond(code: int, reason: str, extra: str = ""):
        cw.write(f"HTTP/1.1 {code} {reason}\r\n{extra}Content-Length: 0\r\n\r\n".encode())

    try:
        head = await asyncio.wait_for(cr.readuntil(b"\r\n\r\n"), timeout=10)
        if len(head) > 16384:
            respond(431, "Request Header Fields Too Large")
            rec["result"] = "headers_too_large"
            return
        lines = head.decode("latin-1").split("\r\n")
        method, target, _ = (lines[0].split(" ") + ["", "", ""])[:3]
        rec["method"] = method
        headers = {}
        for ln in lines[1:]:
            if ":" in ln:
                k, v = ln.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        if method != "CONNECT":
            respond(405, "Method Not Allowed")
            rec["result"] = "method_not_allowed"
            return
        rec["target"] = target
        rec["host_header"] = headers.get("host", "")
        if ARGS.auth:
            got = headers.get("proxy-authorization", "")
            ok = False
            if got.lower().startswith("basic "):
                try:
                    u, _, p = base64.b64decode(got[6:]).decode().partition(":")
                    ok = hmac.compare_digest(u, ARGS.auth) and hmac.compare_digest(p, PASSWORD)
                except Exception:
                    ok = False
            rec["auth"] = "ok" if ok else ("failed" if got else "missing")
            if not ok:
                respond(407, "Proxy Authentication Required", 'Proxy-Authenticate: Basic realm="spike"\r\n')
                rec["result"] = "auth_" + rec["auth"]
                return
        else:
            rec["auth"] = "none"
        if target.startswith("["):
            host, port = target[1:].split("]:")
        else:
            host, port = target.rsplit(":", 1)
        port = int(port)
        if port in ARGS.deny_port:
            respond(403, "Forbidden")
            rec["result"] = "not_allowed"
            return
        try:
            ur, uw = await open_upstream(host, port)
        except BaseException as e:  # noqa: BLE001
            cat = categorize(e)
            respond(HTTP_STATUS[cat], "Bad Gateway" if HTTP_STATUS[cat] == 502 else "Gateway Timeout")
            rec["result"] = cat
            return
        rec["upstream_src"] = uw.get_extra_info("sockname")[0]
        cw.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
        await cw.drain()
        rec["result"] = "ok"
        log(**{**rec, "event": "connected"})
        up, down = await relay(cr, cw, ur, uw)
        rec["bytes_up"], rec["bytes_down"] = up, down
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, ConnectionError, OSError) as e:
        rec.setdefault("result", f"io_error:{type(e).__name__}")
    finally:
        log(**rec)
        try:
            cw.close()
        except Exception:
            pass


async def main():
    handler = socks5_session if ARGS.kind == "socks5" else http_session
    servers = []
    for spec in ARGS.listen:
        host, port = split_listen(spec)
        servers.append(await asyncio.start_server(handler, host, port, reuse_address=True, limit=65536))
    log(event="listening", listen=ARGS.listen, auth="required" if ARGS.auth else "none")
    await asyncio.gather(*(s.serve_forever() for s in servers))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["socks5", "http"])
    ap.add_argument("--listen", action="append", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--auth", default="")
    ap.add_argument("--deny-port", type=int, action="append", default=[])
    ARGS = ap.parse_args()
    PASSWORD = os.environ.get("SPIKE_PROXY_PASSWORD", "")
    asyncio.run(main())
