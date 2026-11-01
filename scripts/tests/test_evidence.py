"""Tests of the end-to-end evidence scripts (m6_evidence.py, with m5_evidence.py's helpers) on synthetic packet
captures and lab logs, so that a check that can never fail, or misses a kind of packet, shows here first.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import contextlib
import datetime
import io
import ipaddress
import os
import struct
import sys
import tempfile
import time
import unittest

os.environ["TZ"] = "UTC"  # the DNS server logs local time; these tests write it in UTC
time.tzset()
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import m5_evidence  # noqa: E402
import m6_evidence  # noqa: E402

PHONE4, PHONE6, GATEWAY = "10.0.2.15", "fec0::15", "10.0.2.2"


def addr(text: str) -> bytes:
    return ipaddress.ip_address(text).packed


def ipv4(src: str, dst: str, proto: int, payload: bytes, fragment_offset: int = 0) -> bytes:
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), 1, fragment_offset, 64, proto, 0, addr(src), addr(dst))
    return header + payload


def ipv6(src: str, dst: str, next_header: int, payload: bytes, extensions: list[tuple[int, bytes]] | None = None) -> bytes:
    """extensions: (header type, body) pairs in order; each body is padded to its header's 8-byte multiple."""
    chain, after = b"", next_header
    for kind, body in reversed(extensions or []):
        if kind == 44:
            block = bytes([after, 0]) + body[:6].ljust(6, b"\0")
        else:
            length = -(-(len(body) + 2) // 8) * 8
            block = bytes([after, length // 8 - 1]) + body.ljust(length - 2, b"\0")
        chain, after = block + chain, kind
    rest = chain + payload
    return struct.pack("!IHBB16s16s", 6 << 28, len(rest), after, 64, addr(src), addr(dst)) + rest


def tcp(sport: int, dport: int, payload: bytes = b"", syn: bool = False, seq: int = 1) -> bytes:
    flags = 0x02 if syn else 0x18
    return struct.pack("!HHIIBBHHH", sport, dport, seq, 0, 5 << 4, flags, 65535, 0, 0) + payload


def udp(sport: int, dport: int, payload: bytes) -> bytes:
    return struct.pack("!HHHH", sport, dport, 8 + len(payload), 0) + payload


ECHO4 = b"\x08\x00\x00\x00\x00\x01\x00\x01ping"
ECHO6 = b"\x80\x00\x00\x00\x00\x01\x00\x01ping"


def dns_query(name: str, kind: int = 1) -> bytes:
    labels = b"".join(bytes([len(part)]) + part.encode() for part in name.split(".")) + b"\0"
    return struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0) + labels + struct.pack("!HH", kind, 1)


def socks_connect_by_name(name: str, port: int) -> bytes:
    return b"\x05\x01\x00\x03" + bytes([len(name)]) + name.encode() + struct.pack("!H", port)


YEAR = datetime.datetime.now(datetime.timezone.utc).year


def at(clock: str) -> datetime.datetime:
    """A time on 1 November of this year (the evidence reads logcat's dates as this year's), UTC."""
    hour, minute, second = (int(part) for part in clock.split(":"))
    return datetime.datetime(YEAR, 11, 1, hour, minute, second, tzinfo=datetime.timezone.utc)


def pcap(path: str, packets: list) -> None:
    """packets: raw IP packets, or (time, packet) pairs; untimed ones are stamped 1970, outside any connected period."""
    with open(path, "wb") as f:
        f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 101))  # raw IP
        for item in packets:
            when, packet = item if isinstance(item, tuple) else (None, item)
            seconds = int(when.timestamp()) if when else 0
            f.write(struct.pack("<IIII", seconds, 0, len(packet), len(packet)) + packet)


def dns_response(name: str, address: str) -> bytes:
    query = dns_query(name)
    answer = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + addr(address)
    return struct.pack("!HHHHHH", 0x1234, 0x8180, 1, 1, 0, 0) + query[12:] + answer


def capture_of(packets: list[bytes]) -> m6_evidence.Capture:
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "c.pcap")
        pcap(path, packets)
        return m6_evidence.read_capture(path)


# The phone reaching both proxies on the runner, the SOCKS5 proxy asked for a lab name: a capture that covers the uplink.
UPLINK = [
    ipv4(PHONE4, GATEWAY, 6, tcp(40000, 1080, syn=True)),
    ipv4(PHONE4, GATEWAY, 6, tcp(40001, 3128, syn=True)),
    ipv4(PHONE4, GATEWAY, 6, tcp(40000, 1080, socks_connect_by_name("proxyflow.test", 8080), seq=2)),
]


class CaptureTest(unittest.TestCase):
    def test_ipv6_packets_to_the_lab_are_seen(self):
        c = capture_of(
            [
                ipv6(PHONE6, "fd99::1", 6, tcp(40000, 8080, syn=True)),
                ipv6(PHONE6, "fd99::1", 17, udp(40001, 9999, b"x")),
                ipv6(PHONE6, "fd99::1", 58, ECHO6),
            ]
        )
        self.assertEqual({"fd99::1": 3}, dict(c.lab_packets))
        self.assertEqual({"[fd99::1]:9999": 1}, dict(c.udp_to_lab))
        self.assertEqual({"fd99::1": 1}, dict(c.echo_requests))
        self.assertEqual(3, c.ipv6_packets)

    def test_extension_headers_are_walked(self):
        c = capture_of(
            [
                ipv6(PHONE6, "fd99::1", 17, udp(40001, 9999, b"x"), extensions=[(0, b"\x01\x02")]),
                ipv6(PHONE6, "fd99::1", 58, ECHO6, extensions=[(0, b""), (60, b"\x01\x04\0\0\0\0")]),
                ipv6(PHONE6, "fd99::1", 58, ECHO6, extensions=[(44, b"\x00\x00\x00\x00\x00\x01")]),  # an atomic fragment
            ]
        )
        self.assertEqual({"[fd99::1]:9999": 1}, dict(c.udp_to_lab))
        self.assertEqual({"fd99::1": 2}, dict(c.echo_requests))
        self.assertEqual(3, c.lab_packets["fd99::1"])

    def test_later_fragments_count_only_as_packets(self):
        c = capture_of([ipv4(PHONE4, "10.99.0.1", 1, b"\x08rest-of-a-big-ping", fragment_offset=185)])
        self.assertEqual({"10.99.0.1": 1}, dict(c.lab_packets))
        self.assertEqual({}, dict(c.echo_requests))

    def test_ipv4_pings_and_udp_to_the_lab_are_seen(self):
        c = capture_of([ipv4(PHONE4, "10.99.0.1", 1, ECHO4), ipv4(PHONE4, "10.99.0.1", 17, udp(40002, 9999, b"x"))])
        self.assertEqual({"10.99.0.1": 1}, dict(c.echo_requests))
        self.assertEqual({"10.99.0.1:9999": 1}, dict(c.udp_to_lab))

    def test_what_the_phone_asked_the_proxies_for(self):
        c = capture_of(UPLINK + [ipv4(PHONE4, GATEWAY, 6, tcp(40003, 3128, b"CONNECT [fd99::1]:8080 HTTP/1.1\r\n\r\n", seq=2))])
        self.assertTrue(c.covers_uplink)
        self.assertEqual({"name proxyflow.test:8080": 1}, dict(c.socks_targets))
        self.assertEqual({"[fd99::1]:8080": 1}, dict(c.connect_targets))

    def test_plaintext_to_a_tls_proxy_port_is_seen(self):
        c = capture_of(
            [
                ipv4(PHONE4, GATEWAY, 6, tcp(40004, 3129, b"\x16\x03\x01\x00\x05hello", seq=2)),
                ipv4(PHONE4, GATEWAY, 6, tcp(40005, 3129, b"CONNECT proxyflow.test:8080 HTTP/1.1\r\n", seq=2)),
            ]
        )
        self.assertEqual({(3129, "TLS"): 1, (3129, "plain"): 1}, dict(c.tls_port_streams))
        self.assertEqual(1, len(c.plaintext_on_tls))

    def test_dns_over_tcp_split_into_two_segments(self):
        query = dns_query("proxyflow.test")
        c = capture_of(
            [
                ipv4(PHONE4, "8.8.8.8", 6, tcp(40008, 53, struct.pack("!H", len(query)), seq=2)),
                ipv4(PHONE4, "8.8.8.8", 6, tcp(40008, 53, query, seq=4)),
            ]
        )
        self.assertEqual({"proxyflow.test": 1}, dict(c.dns_names))

    def test_dns_answers_are_read(self):
        c = capture_of([ipv4("10.0.2.3", PHONE4, 17, udp(53, 40009, dns_response("time.android.com", "216.239.35.0")))])
        self.assertEqual({"216.239.35.0"}, c.dns_answers["time.android.com"])

    def test_answers_cut_off_by_the_capture_length_are_skipped(self):
        response = dns_response("time.android.com", "216.239.35.0")
        c = capture_of([ipv4("10.0.2.3", PHONE4, 17, udp(53, 40010, response[:-2]))])  # the address half cut off
        self.assertEqual(set(), c.dns_answers["time.android.com"])
        self.assertEqual(1, c.packets)

    def test_dns_queries_over_udp_and_tcp(self):
        query = dns_query("proxyflow.test")
        c = capture_of(
            [
                ipv4(PHONE4, "8.8.8.8", 17, udp(40006, 53, query)),
                ipv4(PHONE4, "8.8.8.8", 6, tcp(40007, 53, struct.pack("!H", len(query)) + query, seq=2)),
            ]
        )
        self.assertEqual({"proxyflow.test": 2}, dict(c.dns_names))


class AnnotationTest(unittest.TestCase):
    def annotations(self, lines: list[str]) -> list[tuple[str, str]]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            m5_evidence.annotate("notice", "M6 end-to-end evidence (API 34)", lines)
        found = []
        for printed in out.getvalue().splitlines():
            head, _, message = printed.partition("::")[2].partition("::")
            found.append((head, message.replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")))
        return found

    def test_a_short_evidence_is_one_annotation(self):
        self.assertEqual([("notice title=M6 end-to-end evidence (API 34)", "a\nb")], self.annotations(["a", "b"]))

    def test_a_long_evidence_is_split_where_github_would_cut_it(self):
        lines = [f"fact {n}: " + "x" * 900 for n in range(12)] + ["y" * 8000, "last fact"]
        found = self.annotations(lines)
        self.assertGreater(len(found), 1)
        self.assertEqual([f"notice title=M6 end-to-end evidence (API 34) {n + 1}/{len(found)}" for n in range(len(found))], [h for h, _ in found])
        self.assertTrue(all(len(message) <= m5_evidence.ANNOTATION_CHARS for _, message in found))
        # Nothing is lost: every line is there, in order, a line too long for one annotation in consecutive pieces.
        self.assertEqual("".join(lines), "".join(message.replace("\n", "") for _, message in found))
        self.assertEqual("last fact", found[-1][1].split("\n")[-1])


class EvidenceTest(unittest.TestCase):
    """main() over a small run that passes, and over the same run with one fault at a time."""

    def run_evidence(self, *, capture_extra=(), gate_first="16 03 01", uplink_dns=(), logcat_extra=(), network=(), gate_log="",
                     connecting="", packages="", system_packages="") -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as pf, tempfile.TemporaryDirectory() as out:
            files = {
                "log-baseline.properties": "destination.log=0\n",
                "destination-address.log": "1.0 127.0.0.1:5000 PF6-a-1\n",
                "squid-access.log": "1.0 3 127.0.0.1 TCP_TUNNEL/200 87 CONNECT 10.99.0.2:8080 pfhttp HIER_DIRECT/10.99.0.2 -\n",
                "dns-proxies.log": "".join(
                    f"Nov  1 02:00:00 dnsmasq[1]: 1 127.0.0.1/5000 query[A] {name} from 127.0.0.1\n"
                    for name in ("proxyflow.test", "proxyflow6.test", "missing.proxyflow.test")
                ),
                "dns-uplink.log": "".join(
                    f"Nov  1 {when} dnsmasq[2]: 1 10.0.2.15/5000 query[A] {name} from 10.0.2.15\n"
                    for when, name in (entry.split(" ", 1) if " " in entry else ("02:00:00", entry) for entry in uplink_dns)
                ),
                "gate.log": gate_log or f"1.0 mode forward 127.0.0.1:3129\n1.1 connection 1 from 127.0.0.1:4000 mode=forward first={gate_first}\n",
            }
            for name, text in files.items():
                with open(os.path.join(pf, name), "w") as f:
                    f.write(text)
            with open(os.path.join(out, "guest-network.txt"), "w") as f:
                f.write("".join(line + "\n" for line in network))
            with open(os.path.join(out, "phone-connecting.txt"), "w") as f:
                f.write(connecting)
            with open(os.path.join(out, "packages-uids.txt"), "w") as f:
                f.write(packages)
            with open(os.path.join(out, "packages-system.txt"), "w") as f:
                f.write(system_packages)
            with open(os.path.join(out, "logcat.txt"), "w") as f:
                f.write("11-01 02:00:00.000   100   100 I ProxyFlowE2E: sent PF6-a-1 to 10.99.0.2:8080\n" + "".join(logcat_extra))
            host = os.path.join(out, "uplink.pcap")
            pcap(host, UPLINK + list(capture_extra))
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                status = m6_evidence.main(["m6_evidence.py", "--api", "0", "--pf", pf, "--out", out, "--host-pcap", host])
            return status, printed.getvalue()

    def test_a_clean_run_passes(self):
        status, printed = self.run_evidence()
        self.assertEqual(0, status, printed)

    def test_an_ipv6_packet_to_the_lab_fails(self):
        status, printed = self.run_evidence(capture_extra=[ipv6(PHONE6, "fd99::1", 6, tcp(40010, 8080, syn=True))])
        self.assertEqual(1, status)
        self.assertIn("packets to or from a lab destination's address", printed)

    def test_a_ping_to_the_lab_fails(self):
        status, printed = self.run_evidence(capture_extra=[ipv6(PHONE6, "fd99::1", 58, ECHO6)])
        self.assertEqual(1, status)
        self.assertIn("echo requests (ping) to a lab address", printed)

    def test_udp_to_a_sink_fails(self):
        status, printed = self.run_evidence(capture_extra=[ipv4(PHONE4, "10.99.0.1", 17, udp(40011, 9999, b"x"))])
        self.assertEqual(1, status)
        self.assertIn("UDP datagrams to a lab address", printed)

    def test_plaintext_through_the_gate_to_a_tls_front_fails(self):
        status, printed = self.run_evidence(gate_first="05 02 00")
        self.assertEqual(1, status)
        self.assertIn("plaintext to a TLS proxy through the gate", printed)

    def test_no_tls_connection_through_the_gate_fails(self):
        status, printed = self.run_evidence(gate_first="-")
        self.assertEqual(1, status)
        self.assertIn("no connection went through the gate to a TLS front", printed)
        self.assertNotIn("plaintext to a TLS proxy", printed)

    def test_a_plaintext_retry_after_the_gates_wait_fails(self):
        gate = (
            "1.0 connection 1 from 127.0.0.1:4000 mode=forward upstream=127.0.0.1:3129 first=16 03 01\n"
            "2.0 connection 2 from 127.0.0.1:4001 mode=forward upstream=127.0.0.1:3132 first=- (timeout)\n"
            "17.5 connection 2 late first=05 02 00\n"
        )
        status, printed = self.run_evidence(gate_log=gate)
        self.assertEqual(1, status)
        self.assertIn("plaintext to a TLS proxy through the gate: ['3132: late 05 02 00']", printed)

    def test_the_gate_tells_each_connection_apart(self):
        gate = (
            "1.0 connection 1 from 127.0.0.1:4000 mode=forward upstream=127.0.0.1:3129 first=16 03 01\n"
            "2.0 connection 2 from 127.0.0.1:4001 mode=forward upstream=127.0.0.1:3132 first=- (closed)\n"
            "3.0 connection 3 from 127.0.0.1:4002 mode=forward upstream=127.0.0.1:3132 first=- (timeout)\n"
            "18.0 connection 3 late first=16 03 01\n"
            "4.0 connection 4 from 127.0.0.1:4003 mode=forward upstream=127.0.0.1:1080 first=05 02 00\n"  # plain SOCKS5: fine
        )
        status, printed = self.run_evidence(gate_log=gate)
        self.assertEqual(0, status, printed)
        self.assertIn("gate: 2 connections to a TLS front, every one opening with a TLS record (1 more closed before sending anything)", printed)

    def test_a_lab_name_looked_up_on_the_uplink_fails(self):
        status, printed = self.run_evidence(uplink_dns=["proxyflow.test"])
        self.assertEqual(1, status)
        self.assertIn("looked up lab destination names itself", printed)

    VPN = [
        "11-01 01:59:59.500   200   210 I ProxyFlowVpn: connect p1\n",
        "11-01 02:00:01.000   200   210 I ProxyFlowVpn: connected p1\n",
        "11-01 02:00:30.000   200   210 I ProxyFlowVpn: disconnect\n",
    ]
    PROXY_LOOKUP = "02:00:00 proxy.proxyflow.test"  # during the check: lines the DNS log up when no capture can
    # The phone's connection to its proxy right after "connect": lines the capture up with logcat.
    PROXY_SYN = (at("02:00:00"), ipv4(PHONE4, GATEWAY, 6, tcp(40100, 1080, syn=True)))

    def run_connected(self, uplink_dns=(), capture_extra=(), **more):
        return self.run_evidence(uplink_dns=uplink_dns, logcat_extra=self.VPN, capture_extra=[self.PROXY_SYN, *capture_extra], **more)

    def test_lookups_while_connected_are_lined_up_with_the_vpn_log(self):
        lookups = [self.PROXY_LOOKUP, "02:00:10 connectivitycheck.gstatic.com", "02:00:29 www.youtube.com", "02:00:40 dl.google.com"]
        status, printed = self.run_connected(uplink_dns=lookups)
        # Android's own network check is expected; 02:00:29 is too close to the period's end, 02:00:40 after it.
        self.assertEqual(0, status, printed)
        self.assertIn("lined up through the emulator's capture", printed)
        self.assertIn("Android's network checks and time: {'connectivitycheck.gstatic.com A': 1}; any other: none", printed)

    def test_a_lookup_around_the_vpn_while_connected_fails(self):
        status, printed = self.run_connected(uplink_dns=[self.PROXY_LOOKUP, "02:00:10 www.youtube.com"])
        self.assertEqual(1, status)
        self.assertIn("looked up names on its own network while connected, around the VPN: {'www.youtube.com A': 1}", printed)

    def test_a_capture_that_cannot_be_lined_up_fails(self):
        vpn = [line.replace("11-01 0", "11-01 1") for line in self.VPN]  # ten hours off
        status, printed = self.run_evidence(uplink_dns=[self.PROXY_LOOKUP, "02:00:10 www.youtube.com"], logcat_extra=vpn,
                                            capture_extra=[self.PROXY_SYN])
        self.assertEqual(1, status, printed)
        self.assertIn("could not be lined up with logcat", printed)
        self.assertIn("no capture could be checked for the periods ProxyFlow was connected", printed)

    def test_a_period_logcat_never_closed_is_checked_to_its_end(self):
        open_period = self.VPN[:2] + ["11-01 02:00:40.000   100   100 I ProxyFlowE2E: sent nothing more\n"]
        status, printed = self.run_evidence(
            uplink_dns=[self.PROXY_LOOKUP],
            logcat_extra=open_period,
            capture_extra=[self.PROXY_SYN, (at("02:00:35"), ipv4(PHONE4, "142.250.80.46", 6, tcp(40030, 443, syn=True)))],
        )
        self.assertEqual(1, status)
        self.assertIn("until logcat ends", printed)

    def test_the_period_ends_when_the_test_has_android_revoke_the_vpn(self):
        revoking = self.VPN[:2] + [
            "11-01 02:00:10.000   100   100 I ProxyFlowE2E: revoking the VPN: the permission is withdrawn\n",
            "11-01 02:00:20.000   200   210 I ProxyFlowVpn: revoked by Android\n",
        ]
        status, printed = self.run_evidence(
            uplink_dns=[self.PROXY_LOOKUP],
            logcat_extra=revoking,
            # Apps reconnect on the normal network once Android took the VPN away, before ProxyFlow hears of it.
            capture_extra=[self.PROXY_SYN, (at("02:00:15"), ipv4(PHONE4, "198.19.202.9", 6, tcp(40031, 5228, syn=True)))],
        )
        self.assertEqual(0, status, printed)

    def test_a_direct_connection_while_connected_fails(self):
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[(at("02:00:10"), ipv4(PHONE4, "142.250.80.46", 6, tcp(40020, 443, syn=True)))],
        )
        self.assertEqual(1, status)
        self.assertIn("traffic left the phone around the VPN while connected: {'TCP 142.250.80.46:443': 1}", printed)
        self.assertIn("TCP 142.250.80.46:443 at 02:00:09.500 (connected at 02:00:01 until 'disconnect'; owner not sampled)", printed)

    def test_the_owner_of_a_connection_is_named_from_the_phones_socket_samples(self):
        # /proc/net/tcp: local 10.0.2.15:40020 and remote 142.250.80.46:443 (little-endian hex), SYN-SENT (02), uid 10123.
        sample = f"T {int(at('02:00:10').timestamp())}\n   5: 0F02000A:9C54 2E50FA8E:01BB 02 00000000:00000000 01:00000064 00000002 10123 0 1\n"
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[(at("02:00:10"), ipv4(PHONE4, "142.250.80.46", 6, tcp(40020, 443, syn=True)))],
            connecting=sample,
            packages="package:com.example.leaky uid:10123\n",
        )
        self.assertEqual(1, status)
        self.assertIn("uid 10123 (com.example.leaky)", printed)

    SYN_TO_PLACEHOLDER = (at("02:00:10"), ipv4(PHONE4, "198.19.41.223", 6, tcp(40040, 5228, syn=True)))

    @staticmethod
    def sample(uid: int) -> str:  # /proc/net/tcp: remote 198.19.41.223:5228, SYN-SENT, owned by uid
        return f"T {int(at('02:00:10').timestamp())}\n   7: 1002000A:9C68 DF2913C6:146C 02 00000000:00000000 01:00000064 00000002 {uid} 0 1\n"

    def test_a_preinstalled_app_bypassing_the_vpn_is_reported_as_androids_own(self):
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[self.SYN_TO_PLACEHOLDER],
            connecting=self.sample(10143),
            packages="package:com.google.android.gms uid:10143\n",
            system_packages="package:com.google.android.gms uid:10143\n",
        )
        self.assertEqual(0, status, printed)
        self.assertIn("by Android itself or its preinstalled apps, which it lets bypass VPNs: "
                      "{'TCP 198.19.41.223:5228 (uid 10143 (com.google.android.gms))': 1}", printed)

    def test_a_preinstalled_apps_socket_explains_only_its_own_connection(self):
        # Google Play services' socket (source port 40040) and, to the same address, another connection (40041) that
        # the samples never saw: that one fails.
        other = (at("02:00:10"), ipv4(PHONE4, "198.19.41.223", 6, tcp(40041, 5228, syn=True)))
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[self.SYN_TO_PLACEHOLDER, other],
            connecting=self.sample(10143),
            packages="package:com.google.android.gms uid:10143\n",
            system_packages="package:com.google.android.gms uid:10143\n",
        )
        self.assertEqual(1, status)
        self.assertIn("traffic left the phone around the VPN while connected: {'TCP 198.19.41.223:5228': 1}", printed)
        self.assertIn("owner not sampled", printed)

    def test_udp_is_never_put_down_to_a_preinstalled_apps_tcp_socket(self):
        datagram = (at("02:00:10"), ipv4(PHONE4, "198.19.41.223", 17, udp(40040, 5228, b"x")))
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[datagram],
            connecting=self.sample(10143),
            packages="package:com.google.android.gms uid:10143\n",
            system_packages="package:com.google.android.gms uid:10143\n",
        )
        self.assertEqual(1, status)
        self.assertIn("'UDP 198.19.41.223:5228': 1", printed)

    def test_the_shell_bypassing_the_vpn_fails_even_as_a_system_uid(self):
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[self.SYN_TO_PLACEHOLDER],
            connecting=self.sample(2000),
            packages="package:com.android.shell uid:2000\n",
            system_packages="package:com.android.shell uid:2000\n",
        )
        self.assertEqual(1, status)
        self.assertIn("uid 2000 (com.android.shell)", printed)

    def test_root_bypassing_the_vpn_fails(self):  # adbd left as root would run the tests as root
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[self.SYN_TO_PLACEHOLDER],
            connecting=self.sample(0),
        )
        self.assertEqual(1, status)
        self.assertIn("uid 0 (no package: a system uid)", printed)

    def test_proxyflow_itself_bypassing_the_vpn_elsewhere_than_its_proxy_fails(self):
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[self.SYN_TO_PLACEHOLDER],
            connecting=self.sample(10200),
            packages="package:app.proxyflow.debug uid:10200\n",
            system_packages="package:app.proxyflow.debug uid:10200\n",
        )
        self.assertEqual(1, status)

    def test_a_direct_ipv6_connection_while_connected_fails(self):
        network = ["IPv6 addresses: lo ::1/128;eth0 fec0::15/64;eth0 fe80::15/64"]
        status, printed = self.run_evidence(
            uplink_dns=[self.PROXY_LOOKUP],
            logcat_extra=self.VPN,
            capture_extra=[self.PROXY_SYN, (at("02:00:10"), ipv6(PHONE6, "2607:f8b0:4004:c07::64", 6, tcp(40021, 443, syn=True)))],
            network=network,
        )
        self.assertEqual(1, status)
        self.assertIn("'TCP [2607:f8b0:4004:c07::64]:443': 1", printed)

    def test_the_same_connection_while_disconnected_is_fine(self):
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[(at("02:00:40"), ipv4(PHONE4, "142.250.80.46", 6, tcp(40021, 443, syn=True)))],
        )
        self.assertEqual(0, status, printed)

    def test_a_dns_query_in_the_capture_while_connected_fails(self):
        query = dns_query("www.youtube.com")
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[(at("02:00:12"), ipv4(PHONE4, "10.0.2.3", 17, udp(40022, 53, query)))],
        )
        self.assertEqual(1, status)
        self.assertIn("looked up names on its own network while connected: {'www.youtube.com': 1}", printed)

    def test_a_lookup_cut_off_by_the_capture_length_still_counts(self):
        cut = dns_query("a-very-long-name-of-the-kind-the-phone-capture-cuts-off.example")[:30]
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[(at("02:00:12"), ipv4(PHONE4, "10.0.2.3", 17, udp(40023, 53, cut)))],
        )
        self.assertEqual(1, status)
        self.assertIn("'<unreadable query>': 1", printed)

    def test_androids_own_checks_and_the_gate_control_are_expected(self):
        ntp = "216.239.35.0"
        status, printed = self.run_connected(
            uplink_dns=[self.PROXY_LOOKUP],
            capture_extra=[
                (at("02:00:05"), ipv4(PHONE4, "10.0.2.3", 17, udp(40023, 53, dns_query("time.android.com")))),
                (at("02:00:05"), ipv4("10.0.2.3", PHONE4, 17, udp(53, 40023, dns_response("time.android.com", ntp)))),
                (at("02:00:06"), ipv4(PHONE4, ntp, 17, udp(40024, 123, b"\x23" + b"\0" * 47))),
                (at("02:00:07"), ipv4(PHONE4, GATEWAY, 6, tcp(40025, 1091, syn=True))),
                (at("02:00:08"), ipv4(PHONE4, "10.0.2.3", 6, tcp(40026, 853, syn=True))),
            ],
        )
        self.assertEqual(0, status, printed)
        self.assertIn("its network's resolver or Android's own checks: none; by Android itself or its preinstalled apps, which it lets "
                      "bypass VPNs: none; DNS queries: {'time.android.com': 1}", printed)


if __name__ == "__main__":
    unittest.main()
