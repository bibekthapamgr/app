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


def run(records, *args):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "uplink.pcap")
        with open(path, "wb") as f:
            f.write(pcap(records))
        return subprocess.run([sys.executable, "-I", SCRIPT, path, *args], capture_output=True, text=True)


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

    def test_no_packet_to_the_proxy_fails(self):
        r = run([(100, packet(PHONE, "142.250.1.1", 6, 443))], "203.0.113.5", "44444", "100", "200")
        self.assertEqual(r.returncode, 1)
        self.assertIn("never reached the proxy", r.stdout)


if __name__ == "__main__":
    unittest.main()
