"""Tests of check_release_ready.py on a fake tree: the committed tree passes without --production; each missing or
stale piece is named; --production refuses pending rows, undated sections and leftover Unreleased entries.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
import check_release_ready as crr  # noqa: E402

GRADLE = 'versionCode = 12\n        versionName = "1.0.1"\n'
CHANGELOG = """# Changelog

## [Unreleased]

## [1.0.1 (12)] — 2026-12-20

### Fixed
- A thing.

## [1.0.0 (11)] — 2026-12-01

### Added
- Everything.
"""
HISTORY = """| Version | Verified commit | `android` run | `release` run | On Play | Crash-free | Rollout | Notes |
|---|---|---|---|---|---|---|---|
| 1.0.1 (12) | abc1234 | 42400000001 | 42400000002 | 2026-12-20 | pending | — | — |
| 1.0.0 (11) | 75ce907 | 42366997269 | 42366997278 | 2026-12-01 | 99.9 % | 100 % | — |
"""
POLICY = "*This policy describes ProxyFlow version 1.0.1 (12).*\n"
LISTING = "```listing-notes\nFixed a thing.\n```\n"


def fake_tree(**over: str):
    files = {
        "app/build.gradle.kts": GRADLE,
        "CHANGELOG.md": CHANGELOG,
        "docs/m12/release-history.md": HISTORY,
        "docs/m9/privacy-policy.md": POLICY,
        "docs/m11/store-listing.md": LISTING,
    }
    files.update(over)
    return mock.patch.object(crr, "read", lambda path: files[path])


def lock_ok():
    return mock.patch.object(crr.subprocess, "run", lambda *a, **k: mock.Mock(returncode=0))


class ReleaseReady(unittest.TestCase):
    def test_the_committed_tree_passes(self) -> None:
        self.assertEqual(crr.check(production=False), [])

    def test_a_complete_release_passes_production(self) -> None:
        with fake_tree(), lock_ok():
            self.assertEqual(crr.check(production=True), [])

    def test_each_missing_piece_is_named(self) -> None:
        with lock_ok():
            with fake_tree(**{"CHANGELOG.md": "# Changelog\n\n## [Unreleased]\n"}):
                self.assertTrue(any("no section '## [1.0.1 (12)]'" in p for p in crr.check(False)))
            with fake_tree(**{"CHANGELOG.md": CHANGELOG.replace("## [1.0.1 (12)] — 2026-12-20\n\n### Fixed\n- A thing.\n", "## [1.0.1 (12)] — 2026-12-20\n\n### Fixed\n")}):
                self.assertTrue(any("has no entry" in p for p in crr.check(False)))
            with fake_tree(**{"docs/m12/release-history.md": HISTORY.replace("| 1.0.1 (12) | abc1234 | 42400000001 | 42400000002 | 2026-12-20 | pending | — | — |\n", "")}):
                self.assertTrue(any("no row for 1.0.1 (12)" in p for p in crr.check(False)))
            with fake_tree(**{"app/build.gradle.kts": GRADLE.replace("12", "11").replace("1.0.1", "1.0.0")}):
                problems = crr.check(False)
                self.assertTrue(any("not above the last released code" in p for p in problems), problems)
            with fake_tree(**{"docs/m9/privacy-policy.md": "*This policy describes ProxyFlow version 1.0.0 (11).*\n"}):
                self.assertTrue(any("does not describe version 1.0.1 (12)" in p for p in crr.check(False)))
            with fake_tree(**{"docs/m11/store-listing.md": "```listing-notes\n" + "x" * 501 + "\n```\n"}):
                self.assertTrue(any("over Play's 500" in p for p in crr.check(False)))
        with fake_tree(), mock.patch.object(crr.subprocess, "run", lambda *a, **k: mock.Mock(returncode=1, stdout="PROBLEM changed: says:privacy-strings\n", stderr="")):
            problems = crr.check(False)
            self.assertTrue(any("data behaviour changed" in p and "PROBLEM changed: says:privacy-strings" in p for p in problems), problems)

    def test_release_run_and_production_refuse_pending_undated_and_unreleased(self) -> None:
        with lock_ok():
            with fake_tree(**{"docs/m12/release-history.md": HISTORY.replace("| abc1234 |", "| pending |")}):
                self.assertEqual(crr.check(False), [])
                self.assertTrue(any("still says pending" in p for p in crr.check(False, release_run=True)))
                self.assertTrue(any("still says pending" in p for p in crr.check(True)))
            # Before the release run the release column is pending by nature; before the upload it may not be.
            with fake_tree(**{"docs/m12/release-history.md": HISTORY.replace("| 42400000002 |", "| pending |")}):
                self.assertEqual(crr.check(False, release_run=True), [])
                self.assertTrue(any("release run is still pending" in p for p in crr.check(True)))
            with fake_tree(**{"CHANGELOG.md": CHANGELOG.replace("## [1.0.1 (12)] — 2026-12-20", "## [1.0.1 (12)] — not yet on Google Play")}):
                self.assertEqual(crr.check(False, release_run=True), [])
                problems = crr.check(True)
                self.assertTrue(any("neither dated" in p for p in problems), problems)
            # The tagged commit's heading says "release candidate"; the date is a docs-only commit after the rollout.
            with fake_tree(**{"CHANGELOG.md": CHANGELOG.replace("## [1.0.1 (12)] — 2026-12-20", "## [1.0.1 (12)] — release candidate")}):
                self.assertEqual(crr.check(True), [])
            with fake_tree(**{"CHANGELOG.md": CHANGELOG.replace("## [Unreleased]\n", "## [Unreleased]\n\n### Fixed\n- Left over.\n")}):
                self.assertEqual(crr.check(False), [])
                self.assertTrue(any("[Unreleased] still has entries" in p for p in crr.check(False, release_run=True)))

    def test_a_hand_named_rollback_row_is_seen(self) -> None:
        # The documented scheme is a normal forward name (docs/m12/rollback.md), but a suffixed one is not invisible.
        with lock_ok(), fake_tree(**{"docs/m12/release-history.md": HISTORY + "| 1.0.1-r1 (13) | pending | pending | pending | pending | pending | — | drill |\n"}):
            problems = crr.check(False)
            self.assertTrue(any("versionCode 12 is not above the last released code 13" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
