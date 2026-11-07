"""The published privacy page (scripts/release/privacy_page.py) says what docs/m9/privacy-policy.md says — every heading
and paragraph of the document, in order, is in the page, and the page holds no script, no external resource and no
tracking (a privacy page collects nothing).

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import html
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import privacy_page  # noqa: E402
import privacy_policy  # noqa: E402


def page_texts(page: str) -> list[str]:
    return [html.unescape(re.sub(r"<[^>]+>", "", m)).strip() for m in re.findall(r"<(?:h1|h2|p)[^>]*>(.*?)</(?:h1|h2|p)>", page, re.S)]


class PrivacyPage(unittest.TestCase):
    def test_the_page_says_what_the_document_says(self) -> None:
        document = privacy_policy.document().splitlines()
        wanted = [
            line.lstrip("# ").strip("*")
            for line in document[1:]
            if line.strip() and not line.startswith("The privacy policy the app shows")
        ]
        texts = page_texts(privacy_page.privacy_html())
        for want in wanted:
            self.assertIn(want, texts, want)
        order = [texts.index(w) for w in wanted]
        self.assertEqual(order, sorted(order), "the page's order is not the document's")

    def test_the_contact_is_named_when_given(self) -> None:
        for page in (privacy_page.privacy_html("support@example.test", "Example Dev"), privacy_page.index_html("support@example.test", "Example Dev")):
            self.assertIn('href="mailto:support@example.test"', page)
            self.assertIn("(Example Dev)", page)
        for page in (privacy_page.privacy_html(), privacy_page.index_html()):
            self.assertIn("contact details on ProxyFlow's Google Play page", page)
            self.assertNotIn("mailto:", page)
        self.assertEqual(privacy_page.main(["--out", "/tmp/pf-site-test", "--contact", "not an address"]), 2)

    def test_the_page_fetches_nothing_and_runs_nothing(self) -> None:
        for page in (privacy_page.privacy_html("support@example.test"), privacy_page.index_html()):
            self.assertNotIn("<script", page)
            self.assertNotIn("http://", page)
            self.assertNotRegex(page, r'(src|href)="https?://')
            self.assertIn('<meta name="viewport"', page)
            self.assertIn("<title>", page)

    def test_the_front_page_makes_no_forbidden_claim(self) -> None:
        import check_listing_claims as listing

        text = " ".join(page_texts(privacy_page.index_html()))
        for sentence in listing.sentences(text):
            for claim, pattern in listing.FORBIDDEN:
                for m in pattern.finditer(sentence):
                    self.assertTrue(listing.denied(sentence[: m.start()]), f"{claim}: {sentence}")


if __name__ == "__main__":
    unittest.main()
