"""The public privacy policy (docs/m9/privacy-policy.md, open decision D8) says what the app's own page says (M9).

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import privacy_policy  # noqa: E402


class PrivacyPolicyTest(unittest.TestCase):
    def test_the_document_is_the_apps_text(self) -> None:
        with open(privacy_policy.DOCUMENT, encoding="utf-8") as f:
            committed = f.read()
        self.assertEqual(
            privacy_policy.document(),
            committed,
            "docs/m9/privacy-policy.md is not the app's privacy policy: run python3 scripts/privacy_policy.py --out docs/m9/privacy-policy.md",
        )

    def test_every_section_of_the_page_is_in_it(self) -> None:
        text = privacy_policy.document()
        for heading in ("## Kept on this phone", "## Sent from this phone", "## Android and Google", "## Permissions", "## Changes and contact"):
            self.assertIn(heading, text)
        self.assertIn("connectivitycheck.gstatic.com", text, "the pre-flight and test target")
        self.assertIn("Nothing is sent to ProxyFlow's developer", text)
        self.assertNotIn("\\'", text, "Android's escapes are decoded")


if __name__ == "__main__":
    unittest.main()
