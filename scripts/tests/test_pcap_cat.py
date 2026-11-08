"""Tests of scripts/pcap_cat.py (M12-81): the second capture's records follow the first's; a cut-off last record of the
first is dropped before appending; files that do not fit are left alone; a missing second file is nothing to append.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import pcap_cat  # noqa: E402

import m5_evidence  # noqa: E402  (the evidence reader, to read the result back)


def header(linktype: int = 1, endian: str = "<") -> bytes:
    magic = b"\xd4\xc3\xb2\xa1" if endian == "<" else b"\xa1\xb2\xc3\xd4"
    return magic + struct.pack(endian + "HHiIII", 2, 4, 0, 0, 65535, linktype)


def record(payload: bytes, seconds: int, endian: str = "<") -> bytes:
    return struct.pack(endian + "IIII", seconds, 0, len(payload), len(payload)) + payload


class PcapCat(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.first = os.path.join(self.dir.name, "uplink.pcap")
        self.second = os.path.join(self.dir.name, "uplink-restart.pcap")

    def tearDown(self) -> None:
        self.dir.cleanup()

    def write(self, path: str, data: bytes) -> None:
        with open(path, "wb") as f:
            f.write(data)

    def read(self, path: str) -> bytes:
        with open(path, "rb") as f:
            return f.read()

    def test_the_second_files_records_follow_the_firsts(self) -> None:
        self.write(self.first, header() + record(b"a" * 60, 1) + record(b"b" * 70, 2))
        self.write(self.second, header() + record(b"c" * 80, 3))
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertTrue(ok, why)
        self.assertEqual(self.read(self.first), header() + record(b"a" * 60, 1) + record(b"b" * 70, 2) + record(b"c" * 80, 3))
        self.assertEqual(m5_evidence.read_capture(self.first).packets, 3)
        self.assertIn("96 bytes of records appended", why)

    def test_a_cut_off_last_record_is_dropped_first(self) -> None:
        cut = record(b"b" * 70, 2)[:40]  # the process died mid-record
        self.write(self.first, header() + record(b"a" * 60, 1) + cut)
        self.write(self.second, header() + record(b"c" * 80, 3))
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertTrue(ok, why)
        self.assertEqual(self.read(self.first), header() + record(b"a" * 60, 1) + record(b"c" * 80, 3))
        self.assertIn("40 bytes of a cut-off last record dropped", why)
        self.assertEqual(m5_evidence.read_capture(self.first).packets, 2)

    def test_files_that_do_not_fit_are_left_alone(self) -> None:
        before = header(linktype=1) + record(b"a" * 60, 1)
        self.write(self.first, before)
        self.write(self.second, header(linktype=113) + record(b"c" * 80, 3))
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertFalse(ok)
        self.assertIn("the files differ", why)
        self.assertEqual(self.read(self.first), before)
        self.write(self.second, b"\x0a\x0d\x0d\x0a" + b"\0" * 40)  # pcapng
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertFalse(ok)
        self.assertIn("not a pcap file", why)
        self.assertEqual(self.read(self.first), before)

    def test_a_missing_first_file_takes_the_second(self) -> None:
        self.write(self.second, header() + record(b"c" * 80, 3))
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertTrue(ok, why)
        self.assertIn("stands in for it", why)
        self.assertEqual(self.read(self.first), self.read(self.second))

    def test_no_second_file_is_nothing_to_append(self) -> None:
        before = header() + record(b"a" * 60, 1)
        self.write(self.first, before)
        ok, why = pcap_cat.cat(self.first, self.second)
        self.assertTrue(ok)
        self.assertIn("nothing to append", why)
        self.write(self.second, b"")
        self.assertTrue(pcap_cat.cat(self.first, self.second)[0])
        self.assertEqual(self.read(self.first), before)


if __name__ == "__main__":
    unittest.main()
