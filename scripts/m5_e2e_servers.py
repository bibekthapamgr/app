#!/usr/bin/env python3
"""The servers behind the proxies for the M5 end-to-end tests (CI runner only).

destination ADDRESS PORT LOG
    The destination the tests reach by name (proxyflow.test) through a proxy.
    Each connection gets one line read, answered "PROXYFLOW-DEST <line>", and
    closed; every connection is logged with its peer, even one that sends nothing,
    so scripts/m5_evidence.py can count them against the proxies' logs.

silent ADDRESS PORT
    A "proxy" that accepts connections and never answers, for the pre-flight
    check's timeout and cancel tests.
"""

from __future__ import annotations

import asyncio
import sys
import time


async def destination(address: str, port: int, log_path: str) -> None:
    log = open(log_path, "a", buffering=1, encoding="utf-8")

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=10)
        except (asyncio.TimeoutError, ConnectionError):
            line = b""
        text = line.decode("ascii", "replace").strip()[:120]
        log.write(f"{time.time():.3f} {peer[0]}:{peer[1]} {text or '-'}\n")
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


def main(argv: list[str]) -> int:
    if len(argv) == 5 and argv[1] == "destination":
        asyncio.run(destination(argv[2], int(argv[3]), argv[4]))
    elif len(argv) == 4 and argv[1] == "silent":
        asyncio.run(silent(argv[2], int(argv[3])))
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
