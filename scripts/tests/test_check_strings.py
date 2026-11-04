"""Tests of check_strings.py: the strings that broke M10's build (M10-6) are found with their file, line and name; the
escapes Android takes, a wholly quoted string and markup inside a string pass; a bad escape and a stray quote are found.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import check_strings  # noqa: E402

VALUES = """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
    <!-- A comment's apostrophe: Android's -->
    <string name="good">You\\'re offline: Android\\'s kill switch, \\"quoted\\", a\\nb, \\u00e9, \\@ and \\?</string>
    <string name="quoted">"It's quoted whole"</string>
    <string name="markup">Connected to <xliff:g id="name">%1$s</xliff:g>\\'s proxy</string>
    <string name="broken">Sign in from Android's notification</string>
    <plurals name="counted">
        <item quantity="one">%d site\\'s problem</item>
        <item quantity="other">%d sites' problems</item>
    </plurals>
    <string name="bad_escape">50\\% off</string>
    <string name="bad_unicode">\\u12</string>
    <string name="stray_quote">Say "hi"</string>
    <array name="mixed">
        <item>it's typed</item>
    </array>
    <string name="quoted_markup">"Tap <b>Connect</b>, it's quick"</string>
</resources>
"""


class ProblemsInTest(unittest.TestCase):
    def test_what_android_takes_passes(self) -> None:
        for text in ["You\\'re", "\\\"q\\\"", "a\\nb\\tc", "\\u00E9", "\\@x \\?y", '"It\'s quoted whole"', "", "plain"]:
            self.assertEqual([], check_strings.problems_in(text), text)

    def test_an_apostrophe_without_its_backslash(self) -> None:
        self.assertEqual(["an apostrophe without its backslash (write \\')"], check_strings.problems_in("Android's"))
        self.assertEqual(2, len(check_strings.problems_in("Android's page doesn't open")))

    def test_markup_ends_a_quoted_strings_exemption_and_hash_escapes(self) -> None:
        # aapt2 starts quoting afresh at each span, so a wholly quoted string with markup needs its backslashes.
        quote, apostrophe = 'a double quote without its backslash (write \\")', "an apostrophe without its backslash (write \\')"
        self.assertEqual([quote, apostrophe, quote], check_strings.problems_in('"Tap Connect, it\'s quick"', markup=True))
        self.assertEqual([], check_strings.problems_in('"Tap Connect, it\'s quick"'))
        self.assertEqual([], check_strings.problems_in("\\#1 in \\@ and \\?"))

    def test_bad_escapes_and_stray_quotes(self) -> None:
        self.assertIn("a backslash before '%', which Android does not escape", check_strings.problems_in("50\\% off"))
        self.assertEqual(["a \\u escape without four hex digits after it"], check_strings.problems_in("\\u12"))
        self.assertEqual(["a backslash at the end"], check_strings.problems_in("end\\"))
        self.assertEqual(2, len(check_strings.problems_in('Say "hi"')))


class CheckTest(unittest.TestCase):
    def test_finds_each_bad_string_with_its_file_line_and_name(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            values = root / "feature" / "home" / "src" / "main" / "res" / "values"
            values.mkdir(parents=True)
            (values / "strings.xml").write_text(VALUES, encoding="utf-8")
            # Build output is never checked.
            built = root / "feature" / "home" / "build" / "src" / "main" / "res" / "values"
            built.mkdir(parents=True)
            (built / "strings.xml").write_text(VALUES, encoding="utf-8")

            report = check_strings.check(root)

        where = "feature/home/src/main/res/values/strings.xml"
        self.assertEqual(
            [
                f"{where}:7: broken: an apostrophe without its backslash (write \\')",
                f"{where}:8: counted[other]: an apostrophe without its backslash (write \\')",
                f"{where}:12: bad_escape: a backslash before '%', which Android does not escape",
                f"{where}:13: bad_unicode: a \\u escape without four hex digits after it",
                f"{where}:14: stray_quote: a double quote without its backslash (write \\\")",
                f"{where}:14: stray_quote: a double quote without its backslash (write \\\")",
                f"{where}:15: mixed[0]: an apostrophe without its backslash (write \\')",
                f"{where}:18: quoted_markup: a double quote without its backslash (write \\\")",
                f"{where}:18: quoted_markup: an apostrophe without its backslash (write \\')",
                f"{where}:18: quoted_markup: a double quote without its backslash (write \\\")",
            ],
            report,
        )

    def test_the_repositorys_strings_pass(self) -> None:
        root = pathlib.Path(__file__).resolve().parent.parent.parent
        self.assertEqual([], check_strings.check(root))


if __name__ == "__main__":
    unittest.main()
