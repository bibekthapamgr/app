#!/usr/bin/env python3
"""The servers of the end-to-end lab on the CI runner (scripts/e2e_env.sh starts them).

destination ADDRESS PORT LOG
    A destination the tests reach through a proxy (by name, by address, over IPv6 or
    on the LAN address). Each connection gets one line read, answered
    "PROXYFLOW-DEST <line>", and closed; every connection is logged with its peer,
    even one that sends nothing, so the evidence scripts can count them against the
    proxies' logs.

silent ADDRESS PORT
    A "proxy" that accepts connections and never answers, for the pre-flight
    check's timeout and cancel tests.

tls-destination ADDRESS PORT CERT_AND_KEY LOG
    The HTTPS destination (secure.proxyflow.test): TLS with the lab CA's certificate,
    then the same line exchange ("PROXYFLOW-TLS <line>"). It logs each completed
    handshake with the name the client sent (SNI) and its TLS version: the device's TLS
    client must reach it end to end, through the tunnel, with nothing in between.

udp-sink ADDRESS PORT LOG
    UDP that must never arrive (ProxyFlow rejects UDP on the phone): every datagram
    that does is logged with its peer and length, and answered.

gate ADDRESS PORT CONTROL_PORT UPSTREAM LOG
    A proxy the tests can break while connected. Each connection it accepts is relayed
    to UPSTREAM (host:port, a real proxy) or not, depending on its mode, which the
    instrumentation sets over CONTROL_PORT (127.0.0.1 only; the app's own process
    reaches it as 10.0.2.2, outside the VPN):
        forward HOST:PORT   relay new connections to HOST:PORT
        refuse              stop listening: connections are refused
        silent              accept connections and never answer
    Each line is answered "ok <mode>". The log records every mode change and every
    connection with its mode and the first bytes the client sent (hex, at most 3): a
    TLS record starts 16 03, a SOCKS5 greeting 05.
"""

from __future__ import annotations

import asyncio
import socket
import ssl
import sys
import time


def stamp() -> str:
    return f"{time.time():.3f}"


def peer_of(writer: asyncio.StreamWriter) -> str:
    peer = writer.get_extra_info("peername") or ("?", 0)
    host = peer[0]
    return f"[{host}]:{peer[1]}" if ":" in host else f"{host}:{peer[1]}"


async def destination(address: str, port: int, log_path: str) -> None:
    log = open(log_path, "a", buffering=1, encoding="utf-8")

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = peer_of(writer)
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=10)
        except (asyncio.TimeoutError, ConnectionError):
            line = b""
        text = line.decode("ascii", "replace").strip()[:120]
        log.write(f"{stamp()} {peer} {text or '-'}\n")
        try:
            writer.write(f"PROXYFLOW-DEST {text}\n".encode("ascii", "replace"))
            await writer.drain()
        except ConnectionError:
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, address, port)
    async with server:
        await server.serve_forever()


async def silent(address: str, port: int) -> None:
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while await reader.read(4096):
                pass  # never answers; holds the connection until the client gives up
        except ConnectionError:
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, address, port)
    async with server:
        await server.serve_forever()


async def tls_destination(address: str, port: int, cert_and_key: str, log_path: str) -> None:
    log = open(log_path, "a", buffering=1, encoding="utf-8")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_and_key)
    names: dict[int, str] = {}

    def remember_sni(sock: ssl.SSLObject, name: str | None, _context: ssl.SSLContext) -> None:
        names[id(sock)] = name or "-"

    context.sni_callback = remember_sni

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = peer_of(writer)
        tls = writer.get_extra_info("ssl_object")
        sni = names.pop(id(tls), "-") if tls is not None else "-"
        version = tls.version() if tls is not None else "-"
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=10)
        except (asyncio.TimeoutError, ConnectionError, ssl.SSLError):
            line = b""
        text = line.decode("ascii", "replace").strip()[:120]
        log.write(f"{stamp()} {peer} handshake ok sni={sni} {version} line={text or '-'}\n")
        try:
            writer.write(f"PROXYFLOW-TLS {text}\n".encode("ascii", "replace"))
            await writer.drain()
        except (ConnectionError, ssl.SSLError):
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, address, port, ssl=context, ssl_handshake_timeout=10)
    async with server:
        await server.serve_forever()


async def udp_sink(address: str, port: int, log_path: str) -> None:
    log = open(log_path, "a", buffering=1, encoding="utf-8")

    class Sink(asyncio.DatagramProtocol):
        def connection_made(self, transport: asyncio.BaseTransport) -> None:
            self.transport = transport  # type: ignore[assignment]

        def datagram_received(self, data: bytes, addr: tuple) -> None:
            log.write(f"{stamp()} {addr[0]}:{addr[1]} {len(data)} bytes\n")
            self.transport.sendto(b"PROXYFLOW-UDP " + data[:64], addr)  # type: ignore[attr-defined]

    family = socket.AF_INET6 if ":" in address else socket.AF_INET
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Sink, local_addr=(address, port), family=family)
    await asyncio.Event().wait()


class Gate:
    def __init__(self, address: str, port: int, upstream: str, log_path: str) -> None:
        self.address, self.port = address, port
        self.mode = "forward"
        self.upstream = upstream
        self.server: asyncio.base_events.Server | None = None
        self.log = open(log_path, "a", buffering=1, encoding="utf-8")
        self.connections = 0

    async def listen(self) -> None:
        if self.server is None:
            self.server = await asyncio.start_server(self.handle, self.address, self.port, reuse_address=True)

    async def stop_listening(self) -> None:
        # Only the listening socket closes, as when a proxy's port is shut: new connections are refused, the ones
        # already relayed stay up. No Server.wait_closed(): since Python 3.12 it waits for those connections to end,
        # which held the control reply (and left the gate deaf to the next "forward").
        if self.server is not None:
            self.server.close()
            self.server = None

    async def set_mode(self, command: str) -> str:
        words = command.split()
        if not words:
            return "error empty command"
        if words[0] == "forward" and len(words) == 2 and ":" in words[1]:
            self.mode, self.upstream = "forward", words[1]
            await self.listen()
        elif words[0] == "silent" and len(words) == 1:
            self.mode = "silent"
            await self.listen()
        elif words[0] == "refuse" and len(words) == 1:
            self.mode = "refuse"
            await self.stop_listening()
        else:
            return f"error unknown command {command[:40]!r}"
        description = f"forward {self.upstream}" if self.mode == "forward" else self.mode
        self.log.write(f"{stamp()} mode {description}\n")
        return f"ok {description}"

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.connections += 1
        number, mode, upstream, peer = self.connections, self.mode, self.upstream, peer_of(writer)
        # Each connection says where it was relayed to and how its first bytes came: "first=- (closed)" for a client
        # that sent nothing, "first=- (timeout)" when nothing came in 15 s (anything later is logged as "late first=",
        # never relayed), so the evidence can tell a cancelled dial from a plaintext retry.
        try:
            first = await asyncio.wait_for(reader.read(3), timeout=15)
            why = "" if first else " (closed)"
        except asyncio.TimeoutError:
            first, why = b"", " (timeout)"
        except ConnectionError:
            first, why = b"", " (reset)"
        target = upstream if mode == "forward" else "-"
        self.log.write(f"{stamp()} connection {number} from {peer} mode={mode} upstream={target} first={first.hex(' ') or '-'}{why}\n")
        if mode == "silent" or not first:
            late = why != " (timeout)"
            try:
                while chunk := await reader.read(4096):
                    if not late:
                        late = True
                        self.log.write(f"{stamp()} connection {number} late first={chunk[:3].hex(' ')}\n")
            except ConnectionError:
                pass
            writer.close()
            return
        host, _, port = upstream.rpartition(":")
        try:
            up_reader, up_writer = await asyncio.wait_for(asyncio.open_connection(host, int(port)), timeout=5)
        except (OSError, asyncio.TimeoutError) as e:
            self.log.write(f"{stamp()} connection {number}: upstream {upstream} unreachable: {e}\n")
            writer.close()
            return
        up_writer.write(first)

        async def pipe(src: asyncio.StreamReader, dst: asyncio.StreamWriter) -> None:
            try:
                while chunk := await src.read(65536):
                    dst.write(chunk)
                    await dst.drain()
            except (ConnectionError, OSError):
                pass
            finally:
                try:
                    if dst.can_write_eof():
                        dst.write_eof()
                except (OSError, RuntimeError):
                    pass

        await asyncio.gather(pipe(reader, up_writer), pipe(up_reader, writer))
        writer.close()
        up_writer.close()


async def gate(address: str, port: int, control_port: int, upstream: str, log_path: str) -> None:
    g = Gate(address, port, upstream, log_path)
    await g.set_mode(f"forward {upstream}")

    async def control(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while line := await reader.readline():
                answer = await g.set_mode(line.decode("ascii", "replace").strip())
                writer.write((answer + "\n").encode())
                await writer.drain()
        except ConnectionError:
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(control, "127.0.0.1", control_port)
    async with server:
        await server.serve_forever()


def main(argv: list[str]) -> int:
    if len(argv) == 5 and argv[1] == "destination":
        asyncio.run(destination(argv[2], int(argv[3]), argv[4]))
    elif len(argv) == 4 and argv[1] == "silent":
        asyncio.run(silent(argv[2], int(argv[3])))
    elif len(argv) == 6 and argv[1] == "tls-destination":
        asyncio.run(tls_destination(argv[2], int(argv[3]), argv[4], argv[5]))
    elif len(argv) == 5 and argv[1] == "udp-sink":
        asyncio.run(udp_sink(argv[2], int(argv[3]), argv[4]))
    elif len(argv) == 7 and argv[1] == "gate":
        asyncio.run(gate(argv[2], int(argv[3]), int(argv[4]), argv[5], argv[6]))
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
