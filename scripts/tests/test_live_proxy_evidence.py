"""scripts/live_proxy_evidence.py on synthetic captures: only the proxy inside the window passes; anything else fails."""
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "live_proxy_evidence.py")
PHONE = "10.0.2.15"


def ip4(addr: str) -> bytes:
    return bytes(int(p) for p in addr.split("."))


def packet(src: str, dst: str, proto: int, dport: int, flags: int = 0x02) -> bytes:
    if proto == 6:
        l4 = struct.pack("!HHIIBBHHH", 40000, dport, 0, 0, 5 << 4, flags, 65535, 0, 0)
    elif proto == 17:
        l4 = struct.pack("!HHHH", 40000, dport, 8, 0)
    else:
        l4 = b"\x08\x00\x00\x00\x00\x00\x00\x00"
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(l4), 0, 0, 64, proto, 0, ip4(src), ip4(dst)) + l4
    return b"\x52\x55\x0a\x00\x02\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x08\x00" + ip


def pcap(records: list[tuple[float, bytes]]) -> bytes:
    out = io.BytesIO()
    out.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1))
    for stamp, data in records:
        out.write(struct.pack("<IIII", int(stamp), int((stamp % 1) * 1e6), len(data), len(data)))
        out.write(data)
    return out.getvalue()


def run(records, *args, out: dict | None = None):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "uplink.pcap")
        with open(path, "wb") as f:
            f.write(pcap(records))
        extra = []
        if out is not None:
            for name, text in out.items():
                with open(os.path.join(d, name), "w") as f:
                    f.write(text)
            extra = ["--out", d]
        return subprocess.run([sys.executable, "-I", SCRIPT, path, *args, *extra], capture_output=True, text=True)


def dns_query(name: str) -> bytes:
    body = b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00\x00\x01\x00\x01"
    return b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + body


def udp_packet(src: str, dst: str, dport: int, payload: bytes) -> bytes:
    l4 = struct.pack("!HHHH", 40001, dport, 8 + len(payload), 0) + payload
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(l4), 0, 0, 64, 17, 0, ip4(src), ip4(dst)) + l4
    return b"\x52\x55\x0a\x00\x02\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x08\x00" + ip


def icmp_packet(src: str, dst: str, icmp_type: int) -> bytes:
    l4 = bytes([icmp_type, 0, 0, 0, 0, 0, 0, 0])
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(l4), 0, 0, 64, 1, 0, ip4(src), ip4(dst)) + l4
    return b"\x52\x55\x0a\x00\x02\x02" + b"\x52\x54\x00\x12\x34\x56" + b"\x08\x00" + ip


def hex_endpoint(addr: str, port: int) -> str:
    return "".join(f"{b:02X}" for b in reversed(ip4(addr))) + f":{port:04X}"


# One socket-table sample at [when]: the phone's TCP socket from port 40000 to [dst]:[dport], owned by [uid].
def sample(when: float, dst: str, dport: int, uid: int) -> str:
    return (f"T {when:.3f}\n"
            f"tcp 0: {hex_endpoint(PHONE, 40000)} {hex_endpoint(dst, dport)} 01 00000000:00000000 00:00000000 00000000 "
            f"{uid} 0 12345 1 0000000000000000 20 4 30 10 -1\n")


GRANTS = """Package [com.google.android.gms] (abc):
    userId=10100
    android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS: granted=true
Package [com.example.ordinary] (def):
    userId=10200
"""
PACKAGES = "package:com.google.android.gms uid:10100\npackage:com.example.ordinary uid:10200\n"


class LiveProxyEvidence(unittest.TestCase):
    def test_only_the_proxy_inside_the_window_passes(self):
        records = [
            (90, packet(PHONE, "142.250.1.1", 6, 443)),  # before Connected: direct, allowed
            (100, packet(PHONE, "203.0.113.5", 6, 44444)),  # the proxy
            (120, packet(PHONE, "203.0.113.5", 6, 44444, flags=0x18)),
            (121, packet(PHONE, "10.0.2.2", 17, 67)),  # DHCP: ignored
            (122, packet(PHONE, "224.0.0.251", 17, 5353)),  # multicast: ignored
            (123, packet("10.0.2.2", PHONE, 6, 40000, flags=0x12)),  # inbound: not the phone's
            (210, packet(PHONE, "142.250.1.1", 6, 443)),  # after Disconnect: direct, allowed
        ]
        r = run(records, "203.0.113.5", "44444", "100", "200")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("no leak", r.stdout)
        self.assertIn("2 packets to the proxy 203.0.113.5:44444 over TCP (1 new connections)", r.stdout)
        self.assertIn("1 DHCP packet(s)", r.stdout)

    def test_a_packet_around_the_proxy_fails(self):
        records = [
            (100, packet(PHONE, "203.0.113.5", 6, 44444)),
            (150, packet(PHONE, "8.8.8.8", 17, 53)),  # a DNS query around the proxy
            (160, packet(PHONE, "93.184.216.34", 6, 443)),
        ]
        r = run(records, "203.0.113.5", "44444", "100", "200")
        self.assertEqual(r.returncode, 1)
        self.assertIn("LEAK", r.stdout)
        self.assertIn("udp to 8.8.8.8:53", r.stdout)
        self.assertIn("tcp to 93.184.216.34:443", r.stdout)
        self.assertIn("no owner sampled (no socket samples given)", r.stdout)

    def test_androids_own_privileged_traffic_is_reported_not_failed(self):
        records = [
            (100, packet(PHONE, "203.0.113.5", 6, 44444)),
            (150, packet(PHONE, "142.250.141.188", 6, 5228, flags=0x18)),  # Play services' push connection
            (151, udp_packet(PHONE, "10.0.2.3", 53, dns_query("mtalk.google.com"))),  # its lookup
            (152, icmp_packet(PHONE, "142.251.156.119", 3)),  # the stack answering an inbound packet
        ]
        out = {
            "phone-connecting.txt": sample(149.5, "142.250.141.188", 5228, 10100) + sample(150.5, "142.250.141.188", 5228, 10100),
            "packages-uids.txt": PACKAGES,
            "packages-network-permissions.txt": GRANTS,
        }
        r = run(records, "203.0.113.5", "44444", "100", "200", out=out)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("Android's own", r.stdout)
        self.assertIn("uid 10100 (com.google.android.gms; CONNECTIVITY_USE_RESTRICTED_NETWORKS)", r.stdout)
        self.assertIn("query mtalk.google.com", r.stdout)
        self.assertIn("destination unreachable", r.stdout)
        self.assertIn("no leak", r.stdout)

    def test_an_ordinary_apps_connection_and_a_foreign_lookup_fail(self):
        records = [
            (100, packet(PHONE, "203.0.113.5", 6, 44444)),
            (150, packet(PHONE, "93.184.216.34", 6, 443)),  # an ordinary app's SYN
            (151, udp_packet(PHONE, "10.0.2.3", 53, dns_query("example.org"))),
            (152, icmp_packet(PHONE, "1.1.1.1", 8)),  # a ping
        ]
        out = {
            "phone-connecting.txt": sample(150.2, "93.184.216.34", 443, 10200),
            "packages-uids.txt": PACKAGES,
            "packages-network-permissions.txt": GRANTS,
        }
        r = run(records, "203.0.113.5", "44444", "100", "200", out=out)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("uid 10200 (com.example.ordinary): not allowed around the VPN", r.stdout)
        self.assertIn("query example.org", r.stdout)
        self.assertIn("not Android's own", r.stdout)
        self.assertIn("echo request", r.stdout)

    def test_no_packet_to_the_proxy_fails(self):
        r = run([(100, packet(PHONE, "142.250.1.1", 6, 443))], "203.0.113.5", "44444", "100", "200")
        self.assertEqual(r.returncode, 1)
        self.assertIn("never reached the proxy", r.stdout)


if __name__ == "__main__":
    unittest.main()
