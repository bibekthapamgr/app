#!/usr/bin/env python3
"""Checks the end-to-end lab from the CI runner before the emulator starts (scripts/e2e_env.sh).

    e2e_lab_check.py [--quiet] [--offline] [--no-ipv6] PF_DIR

Every proxy must carry a line to every destination it should (by name, by address, IPv6,
the IPv6-only name, the LAN address), a name the proxies' DNS server does not know must fail
at the proxy, each TLS front must present the certificate it is meant to (valid, wrong name,
expired, untrusted CA: checked here against the run's CA the way a client checks it), the
HTTPS destination must answer through a proxy with the certificate the device will expect,
the gate must relay, the UDP sinks must answer, and the two DNS servers must answer what
they should. Unless --offline, both proxies must also reach the pre-flight check's target
(connectivitycheck.gstatic.com:443), as a user's working proxy does. --offline and
--no-ipv6 are for trying the lab on a machine without Internet access or IPv6; CI uses
neither.

It prints one line per check and exits 1 at the first failure; with --quiet it prints
nothing (for retrying while the servers start). The passwords come from e2e.properties
and are never printed.
"""

from __future__ import annotations

import base64
import hashlib
import ipaddress
import os
import socket
import ssl
import struct
import sys

TIMEOUT = 8


def properties(path: str) -> dict[str, str]:
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def tls_to(sock: socket.socket, server_name: str, ca: str) -> ssl.SSLSocket:
    context = ssl.create_default_context(cafile=ca)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context.wrap_socket(sock, server_hostname=server_name)


def connect(port: int, tls_name: str | None = None, ca: str | None = None) -> socket.socket:
    sock = socket.create_connection(("127.0.0.1", port), timeout=TIMEOUT)
    if tls_name:
        sock = tls_to(sock, tls_name, ca or "")
    return sock


def read_exactly(sock: socket.socket, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError(f"closed after {len(data)} of {n} bytes")
        data += chunk
    return data


def socks5(sock: socket.socket, user: str, password: str, host: str, port: int) -> int:
    """A SOCKS5 CONNECT with username/password; returns the reply code (0 = opened)."""
    sock.sendall(b"\x05\x01\x02")
    if read_exactly(sock, 2) != b"\x05\x02":
        raise ConnectionError("the SOCKS5 proxy did not ask for a username and password")
    sock.sendall(bytes([1, len(user)]) + user.encode() + bytes([len(password)]) + password.encode())
    if read_exactly(sock, 2)[1] != 0:
        raise ConnectionError("the SOCKS5 proxy refused the sign-in")
    try:
        address = ipaddress.ip_address(host)
        target = (b"\x01" if address.version == 4 else b"\x04") + address.packed
    except ValueError:
        target = b"\x03" + bytes([len(host)]) + host.encode()
    sock.sendall(b"\x05\x01\x00" + target + struct.pack("!H", port))
    head = read_exactly(sock, 4)
    rest = {1: 4, 4: 16}.get(head[3])
    if rest is None:
        rest = read_exactly(sock, 1)[0]
    read_exactly(sock, rest + 2)
    return head[1]


def connect_request(sock: socket.socket, user: str, password: str, host: str, port: int) -> int:
    """An HTTP CONNECT with Basic sign-in; returns the status code."""
    authority = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
    credentials = base64.b64encode(f"{user}:{password}".encode()).decode()
    sock.sendall(
        f"CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Authorization: Basic {credentials}\r\n\r\n".encode()
    )
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("the HTTP proxy closed the connection")
        head += chunk
    return int(head.split(b" ", 2)[1])


def exchange(sock: socket.socket, line: str) -> str:
    sock.sendall(line.encode() + b"\n")
    data = b""
    while not data.endswith(b"\n"):
        chunk = sock.recv(256)
        if not chunk:
            break
        data += chunk
    return data.decode("ascii", "replace").strip()


def dns_query(server: str, name: str, qtype: int = 1) -> tuple[int, list[str]]:
    query = struct.pack("!HHHHHH", 0x5046, 0x0100, 1, 0, 0, 0)
    query += b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\0" + struct.pack("!HH", qtype, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(5)
        sock.sendto(query, (server, 53))
        answer = sock.recv(4096)
    rcode, count = answer[3] & 0x0F, struct.unpack("!H", answer[6:8])[0]
    at, addresses = len(query), []
    for _ in range(count):
        at += 2 if answer[at] & 0xC0 == 0xC0 else answer.index(b"\0", at) - at + 1
        rtype, _, _, length = struct.unpack("!HHIH", answer[at : at + 10])
        data = answer[at + 10 : at + 10 + length]
        if rtype in (1, 28):
            addresses.append(str(ipaddress.ip_address(data)))
        at += 10 + length
    return rcode, addresses


def main(argv: list[str]) -> int:
    quiet, offline, ipv6 = "--quiet" in argv, "--offline" in argv, "--no-ipv6" not in argv
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    pf = args[0]
    p = properties(os.path.join(pf, "e2e.properties"))
    ca = os.path.join(pf, "pki", "ca.crt")
    socks_user, socks_pass = p["socks.user"], p["socks.password"]
    http_user, http_pass = p["http.user"], p["http.password"]
    done: list[str] = []

    def check(what: str, ok: bool, detail: str = "") -> None:
        if not ok:
            if not quiet:
                print(f"::error title=E2E lab::{what} failed{': ' + detail if detail else ''}")
            sys.exit(1)
        done.append(what)
        if not quiet:
            print(f"ok  {what}")

    def through_socks(host: str, port: int, proxy_port: int = 1080, tls: bool = False) -> str:
        with connect(proxy_port, p["proxy.name"] if tls else None, ca) as sock:
            code = socks5(sock, socks_user, socks_pass, host, port)
            return exchange(sock, "lab-check") if code == 0 else f"reply {code}"

    def through_http(host: str, port: int, proxy_port: int = 3128, tls: bool = False) -> str:
        with connect(proxy_port, p["proxy.name"] if tls else None, ca) as sock:
            status = connect_request(sock, http_user, http_pass, host, port)
            return exchange(sock, "lab-check") if status == 200 else f"status {status}"

    destinations = [
        (p["destination.name"], int(p["destination.port"])),
        (p["address.destination"], int(p["address.port"])),
        (p["lan.address"], int(p["lan.port"])),
    ]
    if ipv6:
        destinations += [(p["v6.address"], int(p["v6.port"])), (p["v6.name"], int(p["v6.port"]))]
    for host, port in destinations:
        for name, via in (("SOCKS5", through_socks), ("HTTP CONNECT", through_http)):
            got = via(host, port)
            check(f"{name} to {host}:{port}", got == "PROXYFLOW-DEST lab-check", got)

    # A name the proxies' DNS server does not know fails at the proxy (SOCKS5 4: host unreachable).
    missing = p["missing.name"]
    check(f"SOCKS5 to {missing} fails at the proxy", through_socks(missing, 8080) == "reply 4")
    check(f"HTTP CONNECT to {missing} fails at the proxy", through_http(missing, 8080) == "status 503")

    # TLS fronts.
    check("HTTP CONNECT over TLS (:3129)", through_http(p["destination.name"], 8080, int(p["tls.http.port"]), tls=True) == "PROXYFLOW-DEST lab-check")
    check("SOCKS5 over TLS (:1443)", through_socks(p["destination.name"], 8080, int(p["tls.socks.port"]), tls=True) == "PROXYFLOW-DEST lab-check")
    expected = {"tls.other.port": 62, "tls.expired.port": 10, "tls.untrusted.port": 20}  # X509_V_ERR_*
    for key, code in expected.items():
        try:
            connect(int(p[key]), p["proxy.name"], ca).close()
            check(f"the TLS front {key} is refused", False, "the handshake was accepted")
        except ssl.SSLCertVerificationError as e:
            check(f"the TLS front {key} is refused (X509 error {e.verify_code})", e.verify_code == code, e.verify_message)

    # The HTTPS destination through a proxy, with the certificate the device must see.
    with connect(3128) as sock:
        check("HTTP CONNECT to the HTTPS destination", connect_request(sock, http_user, http_pass, p["https.name"], int(p["https.port"])) == 200)
        with tls_to(sock, p["https.name"], ca) as tls:
            fingerprint = hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest()
            check("the HTTPS destination's certificate", fingerprint == p["https.sha256"], fingerprint)
            check("the HTTPS destination answers", exchange(tls, "lab-check") == "PROXYFLOW-TLS lab-check")

    check("the gate relays to Dante", through_socks(p["destination.name"], 8080, int(p["gate.port"])) == "PROXYFLOW-DEST lab-check")

    for address in [p["destination.address"]] + ([p["v6.address"]] if ipv6 else []):
        family = socket.AF_INET6 if ":" in address else socket.AF_INET
        with socket.socket(family, socket.SOCK_DGRAM) as sock:
            sock.settimeout(5)
            sock.sendto(b"lab-check", (address, int(p["udp.port"])))
            check(f"the UDP sink on {address}", sock.recv(256).startswith(b"PROXYFLOW-UDP"))

    check("the proxies' DNS server: proxyflow.test", dns_query("10.99.0.53", p["destination.name"]) == (0, [p["destination.address"]]))
    check("the proxies' DNS server: no IPv4 address for proxyflow6.test", dns_query("10.99.0.53", p["v6.name"]) == (0, []))
    check("the proxies' DNS server: no such name", dns_query("10.99.0.53", p["missing.name"])[0] == 3)
    check("the proxies' DNS server: proxyflow6.test over IPv6", dns_query("10.99.0.53", p["v6.name"], 28) == (0, [p["v6.address"]]))
    check("the phone's DNS server: the proxy's name", dns_query("10.99.0.54", p["proxy.name"]) == (0, [p["proxy.host"]]))
    for name in (p["destination.name"], p["https.name"], p["v6.name"], p["missing.name"]):
        check(f"the phone's DNS server has no address for {name}", dns_query("10.99.0.54", name)[1] == [])

    if not offline:
        check("the phone's DNS server forwards other names", bool(dns_query("10.99.0.54", "connectivitycheck.gstatic.com")[1]))
        for name, via in (("SOCKS5", through_socks), ("HTTP CONNECT", through_http)):
            proxy_port = 1080 if name == "SOCKS5" else 3128
            with connect(proxy_port) as sock:
                opened = socks5(sock, socks_user, socks_pass, "connectivitycheck.gstatic.com", 443) == 0 if name == "SOCKS5" else \
                    connect_request(sock, http_user, http_pass, "connectivitycheck.gstatic.com", 443) == 200
                check(f"{name} reaches connectivitycheck.gstatic.com:443", opened)
                with ssl.create_default_context().wrap_socket(sock, server_hostname="connectivitycheck.gstatic.com") as tls:
                    tls.sendall(b"GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n")
                    status = tls.recv(64).split(b" ", 2)[1]
                    check(f"{name}: connectivitycheck.gstatic.com answers 204", status == b"204", status.decode())
    if quiet:
        return 0
    print(f"{len(done)} checks passed")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except (OSError, ValueError, KeyError, IndexError) as e:
        if "--quiet" not in sys.argv:
            print(f"::error title=E2E lab::{type(e).__name__}: {e}")
        sys.exit(1)
