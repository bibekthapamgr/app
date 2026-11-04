"""Tests of m10_performance.py on synthetic logcat: every measurement read and set against its bar, a missing one and a
connection that opened through a refusing proxy failing the step, a figure past its bar only reported.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import m10_performance  # noqa: E402

PREFIX = "11-04 09:12:34.567  4321  4400 I ProxyFlowPerf: PF-PERF "


def run(lines: list[str]) -> list[str]:
    return [PREFIX + line for line in lines]


GOOD = [
    "connect protocol=socks5 n=20 p50_ms=812 p90_ms=1050 max_ms=1400 uplink_drops=0",
    "connect protocol=http n=20 p50_ms=905 p90_ms=1210 max_ms=1650 uplink_drops=1",
    "setup protocol=socks5 path=direct_to_proxy n=20 p50_ms=2 p90_ms=3 max_ms=5",
    "setup protocol=socks5 path=vpn n=20 p50_ms=9 p90_ms=14 max_ms=30",
    "setup protocol=http path=direct_to_proxy n=20 p50_ms=1 p90_ms=2 max_ms=4",
    "setup protocol=http path=vpn n=20 p50_ms=11 p90_ms=16 max_ms=25",
    "fail_closed proxy=refusing first_ms=12 opened=0 results=FAILED:12:ConnectException,FAILED:9:ConnectException,"
    "FAILED:5003:SocketTimeoutException,FAILED:5002:SocketTimeoutException,UNRESOLVED:UnknownHostException status=Reconnecting",
    "fail_closed proxy=silent first_ms=10015 opened=0 results=FAILED:10015:ConnectException status=Connected",
    "throughput protocol=socks5 direction=down path=proxy_only bytes=16777216 ms=1100 mbit_s=244.0 each_ms=1100,1050,1200",
    "throughput protocol=socks5 direction=up path=proxy_only bytes=16777216 ms=1300 each_ms=1260,1300,1390 mbit_s=206.5",
    "throughput protocol=socks5 direction=down path=vpn bytes=16777216 ms=2200 each_ms=2160,2200,2290 mbit_s=122.0 cpu_ms=1800 cpu_s_per_gb=53.64 cpu_pct_at_50mbit=33.5",
    "throughput protocol=socks5 direction=up path=vpn bytes=16777216 ms=2600 each_ms=2560,2600,2690 mbit_s=103.2 cpu_ms=2100 cpu_s_per_gb=62.58 cpu_pct_at_50mbit=39.1",
    "throughput protocol=http direction=down path=proxy_only bytes=16777216 ms=1200 each_ms=1160,1200,1290 mbit_s=223.7",
    "throughput protocol=http direction=up path=proxy_only bytes=16777216 ms=1400 each_ms=1360,1400,1490 mbit_s=191.7",
    "throughput protocol=http direction=down path=vpn bytes=16777216 ms=2400 each_ms=2360,2400,2490 mbit_s=111.8 cpu_ms=1900 cpu_s_per_gb=56.62 cpu_pct_at_50mbit=35.4",
    "throughput protocol=http direction=up path=vpn bytes=16777216 ms=2700 each_ms=2660,2700,2790 mbit_s=99.4 cpu_ms=2200 cpu_s_per_gb=65.56 cpu_pct_at_50mbit=41.0",
    "memory connections=203 rss_disconnected_kib=150000 rss_idle_kib=160000 rss_peak_kib=172000 go_held_idle_kib=20480 "
    "go_held_kib=30720 go_heap_kib=12000 go_stacks_kib=3200 goroutines_idle=40 goroutines=650 opening_ms=4100",
    "idle seconds=300 cpu_ms=420 cpu_pct=0.140 cpu_s_per_hour=5.0 bytes=12000 goroutines_before=40 goroutines_after=40",
]


def bars(found: list[dict[str, str]]) -> dict[str, bool | None]:
    return {b["bar"]: b["met"] for b in m10_performance.report(found)[1]}


class M10PerformanceTest(unittest.TestCase):
    def test_every_measurement_is_read_and_set_against_its_bar(self):
        found = m10_performance.measurements(run(GOOD) + ["11-04 09:12:35.000  4321  4400 I ProxyFlowE2E: PF-PERF connect protocol=x"])
        self.assertEqual(len(GOOD), len(found), "only ProxyFlowPerf's lines count")
        facts, found_bars, problems = m10_performance.report(found)
        self.assertEqual([], problems)
        self.assertEqual(
            {
                "Time to connected": True,
                "Fail-closed reaction when the proxy dies": True,
                "Throughput down through a local test proxy": True,
                "CPU at 50 Mbit/s": True,
                "Engine memory": True,
                "Battery when idle-connected": None,
            },
            {b["bar"]: b["met"] for b in found_bars},
        )
        values = {b["bar"]: b["value"] for b in found_bars}
        self.assertEqual("1210 ms (the slower protocol's p90)", values["Time to connected"])
        self.assertEqual("111.8 Mbit/s (the slower protocol)", values["Throughput down through a local test proxy"])
        self.assertEqual("41.0 % (the costliest transfer)", values["CPU at 50 Mbit/s"], "the up direction counts too")
        self.assertEqual("30.0 MiB held by the Go runtime at 203 connections", values["Engine memory"])
        text = "\n".join(facts)
        self.assertIn("HTTP p50 905 ms, p90 1210 ms, max 1650 ms (n=20, 1 left out (the emulator's uplink dropped))", text)
        self.assertIn("SOCKS5 122.0 Mbit/s through ProxyFlow, 244.0 straight through the proxy (50 % of it)", text)
        self.assertIn("0 of 5 opened", text)
        self.assertIn("SOCKS5 p50 9 ms, p90 14 ms, max 30 ms (n=20), a TCP connection straight to the proxy p50 2 ms, p90 3 ms", text)
        self.assertIn("throughput down (16 MiB each, the median of 3)", text)
        self.assertIn("so 11.7 MiB more for the connections", text)
        summary = m10_performance.row(found)
        self.assertTrue(summary.startswith("row: connect_p50_socks5=812 connect_p90_socks5=1050 setup_p50_socks5=9 direct_p50_socks5=2 "), summary)
        self.assertIn(" down_http=111.8 down_proxy_only_http=223.7 cpu50_down_http=35.4 ", summary)
        self.assertIn(" fail_closed_ms=12 silent_ms=10015 connections=203 go_held_kib=30720 ", summary)
        self.assertTrue(summary.endswith(" idle_cpu_pct=0.140 idle_s=300"), summary)
        self.assertNotIn("=?", summary)
        self.assertIn("connect_p50_socks5=? ", m10_performance.row([]))

    def test_a_missing_measurement_fails(self):
        without = [line for line in GOOD if not line.startswith("memory ") and "direction=up path=vpn" not in line]
        _, found_bars, problems = m10_performance.report(m10_performance.measurements(run(without)))
        self.assertEqual(3, len(problems), problems)
        self.assertIn('no "memory" measurement', problems[0] + problems[1] + problems[2])
        self.assertTrue(any("throughput protocol=http direction=up path=vpn" in p for p in problems))
        self.assertIsNone(bars(m10_performance.measurements(run(without)))["Engine memory"])
        _, _, nothing = m10_performance.report([])
        self.assertEqual(len(m10_performance.EXPECTED), len(nothing), "with no line at all, every measurement is missing")

    def test_a_connection_through_a_refusing_proxy_fails(self):
        lines = [line for line in GOOD if "proxy=refusing" not in line] + [
            "fail_closed proxy=refusing first_ms=8 opened=1 results=FAILED:8:ConnectException,CONNECTED:30 status=Connected"
        ]
        problems = m10_performance.report(m10_performance.measurements(run(lines)))[2]
        self.assertEqual(1, len(problems), problems)
        self.assertIn("went around it", problems[0])

    def test_figures_past_their_bars_are_reported_not_failed(self):
        slow = [
            line.replace("p90_ms=1210", "p90_ms=3400")
            .replace("first_ms=12 ", "first_ms=1500 ")
            .replace("mbit_s=111.8", "mbit_s=60.2")
            .replace("cpu_pct_at_50mbit=41.0", "cpu_pct_at_50mbit=72.5")
            .replace("go_held_kib=30720", "go_held_kib=61440")
            for line in GOOD
        ]
        found = m10_performance.measurements(run(slow))
        self.assertEqual([], m10_performance.report(found)[2])
        self.assertEqual(
            {False},
            {met for name, met in bars(found).items() if name != "Battery when idle-connected"},
        )
        fewer = [line.replace("connections=203", "connections=150") for line in GOOD]
        self.assertIsNone(bars(m10_performance.measurements(run(fewer)))["Engine memory"], "fewer than 200 open is not the bar's case")

    def test_main_writes_the_baseline_and_fails_only_on_problems(self):
        with tempfile.TemporaryDirectory() as out:
            # The streamed logcat, when it holds more than the dump (a ring buffer that wrapped).
            with open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(run(GOOD[:3])) + "\n")
            with open(os.path.join(out, "logcat-live.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(run(GOOD)) + "\n")
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                status = m10_performance.main(["m10_performance.py", "--api", "36", "--pf", out, "--out", out])
            self.assertEqual(0, status)
            with open(os.path.join(out, "m10-performance.json"), encoding="utf-8") as f:
                saved = json.load(f)
            self.assertEqual("36", saved["api"])
            self.assertEqual(len(GOOD), len(saved["measurements"]))
            self.assertEqual([], saved["problems"])
            self.assertIn("::notice title=M10 performance (API 36)", printed.getvalue())
            self.assertNotIn("::warning", printed.getvalue())
            self.assertNotIn("::error", printed.getvalue())

            with open(os.path.join(out, "logcat-live.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(run([line.replace("mbit_s=111.8", "mbit_s=60.2") for line in GOOD[:-1]])) + "\n")
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                status = m10_performance.main(["m10_performance.py", "--api", "36", "--pf", out, "--out", out])
            self.assertEqual(1, status, "the idle measurement is missing")
            self.assertIn("::warning title=M10 performance past a bar (API 36, an emulator)", printed.getvalue())
            self.assertIn("::error title=M10 performance (API 36)", printed.getvalue())


if __name__ == "__main__":
    unittest.main()
