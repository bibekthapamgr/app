"""Tests of m10_performance.py on synthetic logcat: every measurement read and set against its bar, the figures checked
against their own inputs (the units end to end), a missing one, figures that disagree and a connection that opened through
a refusing proxy failing the step, a figure past its bar only reported, and the summary notice short enough for one
annotation.

The sample figures follow PerformanceEndToEndTest's formulas: 16 MiB transfers (a download timed from its first 64 KiB on),
Mbit/s = bytes × 8 / 1000 / ms, CPU s per GB = CPU ms / 1000 / (the engine's bytes / 10^9), % of a core at 50 Mbit/s =
s per GB × 0.00625 × 100.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
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
    "setup protocol=socks5 path=direct_to_proxy n=20 failed=0 p50_ms=2 p90_ms=3 max_ms=5",
    "setup protocol=socks5 path=vpn n=20 failed=0 p50_ms=9 p90_ms=14 max_ms=30",
    "setup protocol=http path=direct_to_proxy n=20 failed=0 p50_ms=1 p90_ms=2 max_ms=4",
    "setup protocol=http path=vpn n=20 failed=0 p50_ms=11 p90_ms=16 max_ms=25",
    "fail_closed proxy=refusing first_ms=12 connected_probes=2 worst_connected_ms=15 opened=0 "
    "results=FAILED:12:ConnectException:Connected,FAILED:15:ConnectException:Connected,FAILED:5003:SocketTimeoutException:Reconnecting,"
    "FAILED:5002:SocketTimeoutException:Reconnecting,UNRESOLVED:UnknownHostException:Reconnecting status=Reconnecting",
    "fail_closed proxy=silent first_ms=10015 opened=0 results=FAILED:10015:ConnectException status=Connected",
    "throughput protocol=socks5 direction=down path=proxy_only bytes=16777216 n=3 timed_bytes=16711680 ms=550 mbit_s=243.1 "
    "each_mbit_s=238.7,243.1,247.6 setup_ms=30",
    "throughput protocol=socks5 direction=down path=vpn bytes=16777216 n=3 timed_bytes=16711680 ms=1100 mbit_s=121.5 "
    "each_mbit_s=119.4,121.5,122.7 setup_ms=35 cpu_ms=1800 engine_bytes=50400000 cpu_s_per_gb=35.71 cpu_pct_at_50mbit=22.3",
    "throughput protocol=socks5 direction=up path=proxy_only bytes=16777216 n=3 timed_bytes=16777216 ms=650 mbit_s=206.5 "
    "each_mbit_s=191.7,206.5,209.7 setup_ms=31",
    "throughput protocol=socks5 direction=up path=vpn bytes=16777216 n=3 timed_bytes=16777216 ms=1300 mbit_s=103.2 "
    "each_mbit_s=99.4,103.2,104.9 setup_ms=36 cpu_ms=2100 engine_bytes=50600000 cpu_s_per_gb=41.50 cpu_pct_at_50mbit=25.9",
    "throughput protocol=http direction=down path=proxy_only bytes=16777216 n=3 timed_bytes=16711680 ms=600 mbit_s=222.8 "
    "each_mbit_s=215.6,222.8,226.6 setup_ms=29",
    "throughput protocol=http direction=down path=vpn bytes=16777216 n=3 timed_bytes=16711680 ms=1200 mbit_s=111.4 "
    "each_mbit_s=107.0,111.4,113.3 setup_ms=40 cpu_ms=1900 engine_bytes=50450000 cpu_s_per_gb=37.66 cpu_pct_at_50mbit=23.5",
    "throughput protocol=http direction=up path=proxy_only bytes=16777216 n=3 timed_bytes=16777216 ms=700 mbit_s=191.7 "
    "each_mbit_s=186.4,191.7,194.5 setup_ms=33",
    "throughput protocol=http direction=up path=vpn bytes=16777216 n=3 timed_bytes=16777216 ms=1350 mbit_s=99.4 "
    "each_mbit_s=95.9,99.4,100.9 setup_ms=41 cpu_ms=2200 engine_bytes=50700000 cpu_s_per_gb=43.39 cpu_pct_at_50mbit=27.1",
    "cpu_at_50mbit protocol=socks5 direction=up bytes=62500000 ms=10012 mbit_s=49.9 cpu_ms=820 cpu_pct=8.2 engine_bytes=62501000",
    "cpu_at_50mbit protocol=http direction=up bytes=62500000 ms=10020 mbit_s=49.9 cpu_ms=900 cpu_pct=9.0 engine_bytes=62501000",
    "memory traffic=idle connections=203 load_bytes=0 rss_disconnected_kib=150000 rss_idle_kib=160000 rss_peak_kib=161000 "
    "go_held_idle_kib=20480 go_held_kib=30720 go_heap_kib=12000 go_stacks_kib=3200 goroutines_idle=40 goroutines=650 goroutines_closed=44 opening_ms=4100",
    "memory traffic=in_flight connections=200 least_down_bytes=98304 load_bytes=262144 rss_disconnected_kib=150000 rss_idle_kib=160000 rss_peak_kib=196000 "
    "go_held_idle_kib=20480 go_held_kib=40960 go_heap_kib=36000 go_stacks_kib=3300 goroutines_idle=40 goroutines=652 goroutines_closed=45 opening_ms=4300",
    "idle seconds=300 cpu_ms=420 cpu_pct=0.140 cpu_s_per_hour=5.0 bytes=12000 screen_off=true goroutines_before=40 goroutines_after=40 "
    "flows_before=2 flows_after=2",
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
        self.assertEqual("15 ms (the worst while connected; the first 12 ms)", values["Fail-closed reaction when the proxy dies"])
        self.assertEqual("111.4 Mbit/s (the slower protocol)", values["Throughput down through a local test proxy"])
        self.assertEqual(
            "23.5 % (the costliest: uploads paced at 50 Mbit/s 8.2/9.0 %, downloads by their rate 22.3/23.5 %)", values["CPU at 50 Mbit/s"]
        )
        # The bar's case is the one with data in flight; 40 MiB is 41.9 MB of 10^6 bytes.
        self.assertEqual("40.0 MiB (41.9 MB) held by the Go runtime at 200 connections, data in flight", values["Engine memory"])
        text = "\n".join(facts)
        self.assertIn("HTTP p50 905 ms, p90 1210 ms, max 1650 ms (n=20, 1 left out (the emulator's uplink dropped))", text)
        self.assertIn("SOCKS5 121.5 Mbit/s through ProxyFlow (each 119.4,121.5,122.7; set-up 35 ms), 243.1 straight through the proxy", text)
        self.assertIn("(50 % of it)", text)
        self.assertIn("0 of 5 opened", text)
        self.assertIn("the longest any of the 2 made while the session was still connected waited then: 15 ms", text)
        self.assertIn("SOCKS5 p50 9 ms, p90 14 ms, max 30 ms (n=20), a TCP connection straight to the proxy p50 2 ms, p90 3 ms", text)
        self.assertIn("throughput down (16 MiB each, the median of 3, timed without its set-up)", text)
        self.assertIn("each with 262144 bytes in flight (ProxyFlow had handed each at least 98304 bytes on when read)", text)
        self.assertIn("so 44.9 MiB (47.1 MB) more than before connecting", text)
        self.assertIn("an upload paced at it, 62500000 bytes in 10012 ms (49.9 Mbit/s): 820 ms of CPU, 8.2 % of one core", text)
        summary = m10_performance.row(found)
        self.assertTrue(summary.startswith("row: connect_p50_socks5=812 connect_p90_socks5=1050 setup_p50_socks5=9 direct_p50_socks5=2 "), summary)
        self.assertIn(" down_http=111.4 down_proxy_only_http=222.8 cpu50_down_http=23.5 ", summary)
        self.assertIn(" cpu50_paced_http=9.0 fail_closed_ms=12 fail_closed_worst_ms=15 silent_ms=10015 connections=203 go_held_kib=30720 ", summary)
        self.assertIn(" loaded_connections=200 loaded_go_held_kib=40960 loaded_rss_peak_kib=196000 ", summary)
        self.assertTrue(summary.endswith(" idle_cpu_pct=0.140 idle_s=300"), summary)
        self.assertNotIn("=?", summary)
        self.assertIn("connect_p50_socks5=? ", m10_performance.row([]))

    def test_the_units_are_checked_end_to_end(self):
        # A throughput logged for 32 MiB with 16 MiB's time (the formula's size and the transfer's apart), CPU per GB over
        # the test's own bytes instead of the engine's, and a paced share of a core off by a factor of ten.
        wrong = {
            "mbit_s=121.5 ": "mbit_s=243.0 ",
            "cpu_s_per_gb=37.66": "cpu_s_per_gb=56.62",
            "cpu_pct=8.2 ": "cpu_pct=0.8 ",
        }
        lines = list(GOOD)
        for old, new in wrong.items():
            lines = [line.replace(old, new) for line in lines]
        problems = m10_performance.report(m10_performance.measurements(run(lines)))[2]
        self.assertEqual(4, len(problems), problems)
        self.assertTrue(all(p.startswith("figures that disagree (units?): ") for p in problems), problems)
        text = "\n".join(problems)
        self.assertIn("throughput protocol=socks5 direction=down path=vpn: 243.0 Mbit/s, but 16711680 bytes in 1100 ms are 121.5", text)
        self.assertIn("throughput protocol=http direction=down path=vpn: 56.62 CPU s per GB, but 1900 ms over 50450000 bytes are 37.66", text)
        self.assertIn("cpu_at_50mbit protocol=socks5 direction=up: 0.8 % of a core, but 820 ms of CPU in 10012 ms are 8.2", text)
        # The share at 50 Mbit/s no longer follows from the wrong per-GB figure either.
        self.assertIn("23.5 % at 50 Mbit/s, but 56.62 s per GB give 35.4", text)
        # Each figure's own rounding is allowed.
        self.assertEqual([], m10_performance.inconsistencies(m10_performance.measurements(run(GOOD))))

    def test_a_missing_measurement_fails(self):
        without = [line for line in GOOD if not line.startswith("memory traffic=in_flight") and "direction=up path=vpn" not in line]
        _, _, problems = m10_performance.report(m10_performance.measurements(run(without)))
        self.assertEqual(3, len(problems), problems)
        self.assertTrue(any('no "memory traffic=in_flight" measurement' in p for p in problems), problems)
        self.assertTrue(any("throughput protocol=http direction=up path=vpn" in p for p in problems))
        # Without the in-flight case, the bar falls back to the idle one, and says so.
        self.assertTrue(bars(m10_performance.measurements(run(without)))["Engine memory"])
        _, _, nothing = m10_performance.report([])
        self.assertEqual(len(m10_performance.EXPECTED), len(nothing), "with no line at all, every measurement is missing")

    def test_a_connection_through_a_refusing_proxy_fails(self):
        lines = [line for line in GOOD if "proxy=refusing" not in line] + [
            "fail_closed proxy=refusing first_ms=8 connected_probes=1 worst_connected_ms=8 opened=1 "
            "results=FAILED:8:ConnectException:Connected,CONNECTED:30:Connected status=Connected"
        ]
        problems = m10_performance.report(m10_performance.measurements(run(lines)))[2]
        self.assertEqual(1, len(problems), problems)
        self.assertIn("went around it", problems[0])

    def test_figures_past_their_bars_are_reported_not_failed(self):
        slow = [
            line.replace("p90_ms=1210", "p90_ms=3400")
            .replace("worst_connected_ms=15 ", "worst_connected_ms=1500 ")
            .replace("ms=1200 mbit_s=111.4", "ms=2200 mbit_s=60.8")
            .replace("cpu_ms=900 cpu_pct=9.0", "cpu_ms=7300 cpu_pct=72.9")
            .replace("go_held_kib=40960", "go_held_kib=61440")
            for line in GOOD
        ]
        found = m10_performance.measurements(run(slow))
        self.assertEqual([], m10_performance.report(found)[2])
        self.assertEqual(
            {False},
            {met for name, met in bars(found).items() if name != "Battery when idle-connected"},
        )
        fewer = [line.replace("connections=200", "connections=150") for line in GOOD]
        self.assertIsNone(bars(m10_performance.measurements(run(fewer)))["Engine memory"], "fewer than 200 open is not the bar's case")
        # 50 MB is 10^6 bytes each: 48,828 KiB is within it, 48,829 past.
        at = [line.replace("go_held_kib=40960", "go_held_kib=48828") for line in GOOD]
        self.assertTrue(bars(m10_performance.measurements(run(at)))["Engine memory"])
        past = [line.replace("go_held_kib=40960", "go_held_kib=48829") for line in GOOD]
        self.assertFalse(bars(m10_performance.measurements(run(past)))["Engine memory"])

    def test_a_paced_upload_counts_only_at_50_mbit_s(self):
        # Paced at 20 Mbit/s (the path carried no more), its CPU would understate 50 Mbit/s': left out of the bar, and said.
        slow = [
            line.replace("bytes=62500000 ms=10020 mbit_s=49.9 cpu_ms=900 cpu_pct=9.0", "bytes=25000000 ms=10000 mbit_s=20.0 cpu_ms=300 cpu_pct=3.0")
            for line in GOOD
        ]
        facts, found_bars, problems = m10_performance.report(m10_performance.measurements(run(slow)))
        self.assertEqual([], problems)
        value = {b["bar"]: b["value"] for b in found_bars}["CPU at 50 Mbit/s"]
        # HTTP's uploads stand in for it, by their rate: a CPU-bound upload that never reached 50 Mbit/s still counts.
        self.assertEqual(
            "27.1 % (the costliest: uploads paced at 50 Mbit/s 8.2 %, uploads by their rate 27.1 % (HTTP: not paced at 50 Mbit/s), "
            "downloads by their rate 22.3/23.5 %)",
            value,
        )
        bound = [line.replace("cpu_s_per_gb=43.39 cpu_pct_at_50mbit=27.1", "cpu_s_per_gb=120.00 cpu_pct_at_50mbit=75.0") for line in slow]
        bound = [line.replace("cpu_ms=2200 engine_bytes=50700000", "cpu_ms=6084 engine_bytes=50700000") for line in bound]
        self.assertFalse(bars(m10_performance.measurements(run(bound)))["CPU at 50 Mbit/s"])
        self.assertIn("(20.0 Mbit/s): 300 ms of CPU, 3.0 % of one core; left out of the bar: not within 5 % of 50 Mbit/s", "\n".join(facts))
        # A paced upload that ran on its second try says so.
        retried = [line.replace("cpu_pct=9.0 engine_bytes=62501000", "cpu_pct=9.0 engine_bytes=62501000 tries=2") for line in GOOD]
        facts = m10_performance.report(m10_performance.measurements(run(retried)))[0]
        self.assertIn("9.0 % of one core; 2 tries, the emulator's uplink dropped the first 1", "\n".join(facts))
        self.assertNotIn("tries", "\n".join(m10_performance.report(m10_performance.measurements(run(GOOD)))[0]))
        # 47.5 and 52.5 Mbit/s are within 5 %; 47.4 is not.
        for rate, counted in (("47.5", True), ("52.5", True), ("47.4", False)):
            lines = [line.replace("cpu_pct=8.2 ", "cpu_pct=30.0 ").replace("mbit_s=49.9 cpu_ms=820", f"mbit_s={rate} cpu_ms=820") for line in GOOD]
            found = m10_performance.measurements(run(lines))
            value = {b["bar"]: b["value"] for b in m10_performance.report(found)[1]}["CPU at 50 Mbit/s"]
            self.assertEqual(counted, value.startswith("30.0 % "), (rate, value))

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
            notices = [line for line in printed.getvalue().splitlines() if line.startswith("::notice")]
            # The summary — the row and the bars — first and in one annotation; the figures after it.
            self.assertTrue(notices[0].startswith("::notice title=M10 performance (API 36)::API 36%0Arow: "), notices[0][:80])
            self.assertLess(len(notices[0]), 3600, "one annotation: GitHub keeps 10 notices a step, M5–M8's first")
            self.assertTrue(notices[1].startswith("::notice title=M10 performance, the figures (API 36)"), notices[1][:80])
            self.assertNotIn("::warning", printed.getvalue())
            self.assertNotIn("::error", printed.getvalue())

            with open(os.path.join(out, "logcat-live.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(run([line.replace("ms=1200 mbit_s=111.4", "ms=2200 mbit_s=60.8") for line in GOOD[:-1]])) + "\n")
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                status = m10_performance.main(["m10_performance.py", "--api", "36", "--pf", out, "--out", out])
            self.assertEqual(1, status, "the idle measurement is missing")
            self.assertIn("::warning title=M10 performance past a bar (API 36, an emulator)", printed.getvalue())
            self.assertIn("::error title=M10 performance (API 36)", printed.getvalue())

    def test_a_script_failure_is_an_error_annotation(self):
        with tempfile.TemporaryDirectory() as out:
            with open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8") as f:
                f.write("\n".join(run(GOOD)) + "\n")
            script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "m10_performance.py")
            # --out names a file, so the report can't be written: the wrapper says so as an error annotation.
            blocker = os.path.join(out, "blocked")
            with open(blocker, "w", encoding="utf-8") as f:
                f.write("")
            result = subprocess.run(
                [sys.executable, script, "--api", "29", "--pf", out, "--out", blocker], capture_output=True, text=True, check=False
            )
            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn("::error title=M10 performance (API 29)::the report script failed: ", result.stdout)


if __name__ == "__main__":
    unittest.main()
