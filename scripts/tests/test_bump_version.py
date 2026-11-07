"""Tests of bump_version.py on a copy of the tree: the version goes up everywhere at once, the changelog section takes
the Unreleased entries, the history gets a pending row, the policy's version line follows; an older or equal version
and a repeated section are refused.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
SCRIPT = os.path.join(ROOT, "scripts", "release", "bump_version.py")
COPIED = [
    "app/build.gradle.kts",
    "CHANGELOG.md",
    "docs/m12/release-history.md",
    "docs/m9/privacy-policy.md",
    "scripts/privacy_policy.py",
    "feature/about/src/main/res/values/strings.xml",
    "feature/about/src/main/kotlin/app/proxyflow/feature/about/PrivacyPolicyScreen.kt",
]


class Bump(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        for rel in COPIED:
            dst = os.path.join(self.dir.name, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(os.path.join(ROOT, rel), dst)
        with open(os.path.join(self.dir.name, "CHANGELOG.md"), encoding="utf-8") as f:
            text = f.read()
        with open(os.path.join(self.dir.name, "CHANGELOG.md"), "w", encoding="utf-8") as f:
            f.write(text.replace("## [Unreleased]\n", "## [Unreleased]\n\n### Fixed\n- Something held.\n", 1))

    def tearDown(self) -> None:
        self.dir.cleanup()

    def run_bump(self, name: str) -> subprocess.CompletedProcess:
        env = dict(os.environ, PROXYFLOW_ROOT=self.dir.name)
        return subprocess.run([sys.executable, SCRIPT, name], capture_output=True, text=True, env=env)

    def read(self, rel: str) -> str:
        with open(os.path.join(self.dir.name, rel), encoding="utf-8") as f:
            return f.read()

    def test_everything_moves_together(self) -> None:
        result = self.run_bump("1.0.1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('versionName = "1.0.1"', self.read("app/build.gradle.kts"))
        self.assertIn("versionCode = 12", self.read("app/build.gradle.kts"))
        changelog = self.read("CHANGELOG.md")
        self.assertIn("## [1.0.1 (12)] — not yet on Google Play\n\n### Fixed\n- Something held.", changelog)
        self.assertIn("## [Unreleased]\n\n## [1.0.1 (12)]", changelog)
        self.assertIn("[1.0.1 (12)]: https://github.com/bibekthapamgr/app/releases/tag/v1.0.1", changelog)
        self.assertIn("| 1.0.1 (12) | pending | pending | pending | pending | pending | — | — |", self.read("docs/m12/release-history.md"))
        self.assertIn("version 1.0.1 (12)", self.read("docs/m9/privacy-policy.md"))

    def test_an_older_or_equal_version_is_refused(self) -> None:
        for name in ("1.0.0", "0.9.9", "1.0", "abc"):
            with self.subTest(name=name):
                self.assertNotEqual(self.run_bump(name).returncode, 0)
        self.assertEqual(self.read("app/build.gradle.kts"), open(os.path.join(ROOT, "app/build.gradle.kts"), encoding="utf-8").read())

    def test_a_second_bump_to_the_same_name_is_refused(self) -> None:
        self.assertEqual(self.run_bump("1.0.1").returncode, 0)
        self.assertNotEqual(self.run_bump("1.0.1").returncode, 0)


if __name__ == "__main__":
    unittest.main()
