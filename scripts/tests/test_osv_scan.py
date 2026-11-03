"""Tests of scripts/osv_scan.py (M8) on a synthetic Gradle dependency tree and OSV answers, so a scan that can never
fail, or reads the tree wrongly, shows here first.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import osv_scan  # noqa: E402

TREE = """
releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core:designsystem
|    +--- androidx.compose:compose-bom:2025.01.00
|    |    \\--- androidx.compose.ui:ui:1.7.6 (c)
|    +--- androidx.compose.ui:ui -> 1.7.6
|    |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1
|    |    \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.1.0} -> 2.1.0
|    \\--- com.squareup.okio:okio:3.9.0 (*)
\\--- com.example:vulnerable:1.0.0
""".splitlines()

TODAY = datetime.date(2026, 11, 4)


class FakeOsv:
    """OSV as far as the scan asks it: advisories per library, and each advisory's record."""

    def __init__(self, by_library: dict[str, list[dict]]):
        self.by_library = by_library
        self.asked: list[tuple[str, str]] = []

    def __call__(self, url: str, body: dict | None) -> dict:
        if url == osv_scan.QUERY_BATCH:
            results = []
            for q in body["queries"]:
                self.asked.append((q["package"]["name"], q["version"]))
                results.append({"vulns": [{"id": a["id"]} for a in self.by_library.get(q["package"]["name"], [])]})
            return {"results": results}
        wanted = url.removeprefix(osv_scan.VULN)
        return next(a for advisories in self.by_library.values() for a in advisories if a["id"] == wanted)


def advisory(advisory_id: str, severity: str | None = None, vector: str | None = None) -> dict:
    record = {"id": advisory_id, "summary": f"{advisory_id} summary"}
    if severity:
        record["database_specific"] = {"severity": severity}
    if vector:
        record["severity"] = [{"type": "CVSS_V3", "score": vector}]
    return record


class OsvScanTest(unittest.TestCase):
    def test_the_tree_is_read_at_resolved_versions_without_projects_or_constraints(self):
        self.assertEqual(
            {
                "androidx.compose:compose-bom": "2025.01.00",
                "androidx.compose.ui:ui": "1.7.6",
                "androidx.annotation:annotation": "1.9.1",
                "org.jetbrains.kotlin:kotlin-stdlib": "2.1.0",
                "com.squareup.okio:okio": "3.9.0",
                "com.example:vulnerable": "1.0.0",
            },
            osv_scan.resolved_libraries(TREE),
        )

    def test_a_high_or_critical_advisory_fails_and_a_moderate_one_is_reported(self):
        osv = FakeOsv({
            "com.example:vulnerable": [advisory("GHSA-high", severity="HIGH"), advisory("GHSA-moderate", severity="MODERATE")],
            "com.squareup.okio:okio": [advisory("CVE-critical", vector="CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")],
        })
        facts, problems = osv_scan.check(TREE, {}, osv, TODAY)
        self.assertIn(("com.example:vulnerable", "1.0.0"), osv.asked)
        self.assertEqual(2, len(problems), problems)
        self.assertTrue(any("GHSA-high HIGH (the advisory's own rating)" in p for p in problems))
        self.assertTrue(any("CVE-critical CRITICAL (CVSS v3 9.8)" in p for p in problems))
        self.assertTrue(any("GHSA-moderate MEDIUM" in f for f in facts))

    def test_an_unrated_advisory_fails_closed(self):
        osv = FakeOsv({"com.example:vulnerable": [advisory("OSV-unrated", vector="CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N")]})
        _, problems = osv_scan.check(TREE, {}, osv, TODAY)
        self.assertEqual(1, len(problems))
        self.assertIn("UNKNOWN (no rating and no CVSS v3 vector)", problems[0])

    def test_an_allowlisted_advisory_passes_until_its_review_date(self):
        osv = FakeOsv({"com.example:vulnerable": [advisory("GHSA-high", severity="HIGH")]})
        entry = {"package": "com.example:vulnerable", "reason": "only its server half is affected", "review_by": "2027-01-31"}
        facts, problems = osv_scan.check(TREE, {"GHSA-high": entry}, osv, TODAY)
        self.assertEqual([], problems)
        self.assertTrue(any("allowed until 2027-01-31: only its server half is affected" in f for f in facts))

        _, problems = osv_scan.check(TREE, {"GHSA-high": {**entry, "review_by": "2026-11-03"}}, osv, TODAY)
        self.assertIn("expired", problems[0])
        _, problems = osv_scan.check(TREE, {"GHSA-high": {**entry, "package": "com.example:other"}}, osv, TODAY)
        self.assertIn("not com.example:vulnerable", problems[0])
        _, problems = osv_scan.check(TREE, {"GHSA-high": {**entry, "reason": " "}}, osv, TODAY)
        self.assertIn("without a reason", problems[0])

    def test_nothing_known_passes_and_an_unread_tree_fails(self):
        facts, problems = osv_scan.check(TREE, {}, FakeOsv({}), TODAY)
        self.assertEqual([], problems)
        self.assertIn("OSV knows no vulnerability in any of them", facts)
        self.assertEqual(1, len(osv_scan.check(["BUILD SUCCESSFUL"], {}, FakeOsv({}), TODAY)[1]))

    def test_an_incomplete_answer_is_not_taken_as_clean(self):
        def short(url, body):
            return {"results": []}

        with self.assertRaises(RuntimeError):
            osv_scan.check(TREE, {}, short, TODAY)

    def test_cvss_scores_follow_the_specification(self):
        for vector, score in (
            ("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", 9.8),
            ("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", 10.0),
            ("CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:L/I:N/A:N", 3.1),
            ("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N", 5.5),
            ("CVSS:3.0/AV:N/AC:L/PR:L/UI:N/S:C/C:L/I:L/A:N", 6.4),
            ("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N", 0.0),
        ):
            self.assertEqual(score, osv_scan.cvss3_score(vector), vector)
        self.assertIsNone(osv_scan.cvss3_score("CVSS:4.0/AV:N"))
        self.assertIsNone(osv_scan.cvss3_score("CVSS:3.1/AV:N"), "a vector without its metrics is not scored")


if __name__ == "__main__":
    unittest.main()
