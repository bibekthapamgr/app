"""No source file holds a raw Unicode bidi control (M10-117): an embedding, override or isolate written as itself
rather than as an escape lays the rest of its line out in another order in editors and in GitHub's diffs ("Trojan
Source"), so what a reviewer reads is not what the compiler does. Text in right-to-left scripts is fine; only the
invisible controls are refused. ProxyFlow writes them as escapes ("\\u2068").

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import unittest

# LRE, RLE, PDF, LRO, RLO; LRI, RLI, FSI, PDI.
CONTROLS = re.compile("[\u202a-\u202e\u2066-\u2069]")
ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def controls_in(text: str) -> list[tuple[int, str]]:
    """Each line holding a raw control, with the controls' code points."""
    return [(n, " ".join(f"U+{ord(c):04X}" for c in CONTROLS.findall(line))) for n, line in enumerate(text.split("\n"), 1)
            if CONTROLS.search(line)]


class NoBidiControlsTest(unittest.TestCase):
    def test_controls_are_found_and_escapes_are_not(self) -> None:
        self.assertEqual([(2, "U+2068 U+2069")], controls_in('val a = 1\nval b = "\u2068x\u2069"'))
        self.assertEqual([], controls_in('val b = "\\u2068x\\u2069"'))
        self.assertEqual([], controls_in("مكتب 2 · מִשְׂרָד"))

    def test_the_repositorys_files_hold_none(self) -> None:
        files = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout.split(b"\0")
        found = []
        for name in filter(None, files):
            try:
                text = (ROOT / name.decode()).read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue  # binary files (screenshots, keystores) and files gone from the work tree
            found += [f"{name.decode()}:{line}: {points}" for line, points in controls_in(text)]
        self.assertEqual([], found)


if __name__ == "__main__":
    unittest.main()
