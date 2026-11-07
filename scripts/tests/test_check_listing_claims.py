"""Tests of check_listing_claims.py: the forbidden claims caught in every block and caption (and allowed only when the
sentence denies them), Play's lengths and title words, placeholders, and the limitations the full description must state.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import check_listing_claims as listing  # noqa: E402

FULL = (
    "ProxyFlow sends your apps' TCP connections through a proxy. It uses Android's VPN feature; there is no VPN server. "
    "UDP is blocked and ping is not answered. Your proxy provider can see which sites you open. "
    "It does not make you anonymous. The privacy policy is in the app."
)


def doc(title="ProxyFlow", short="Your apps' TCP connections, through a proxy you already have.", full=FULL, notes="First release.", captions=("One", "Two")):
    rows = "\n".join(f"| {i} | `shot_{i}_light` | {c} |" for i, c in enumerate(captions, start=1))
    return f"""# Listing

```listing-title
{title}
```

```listing-short
{short}
```

```listing-full
{full}
```

| # | Source | Caption |
|---|---|---|
{rows}

```listing-notes
{notes}
```
"""


class Claims(unittest.TestCase):
    def test_a_clean_listing_passes(self) -> None:
        self.assertEqual(listing.check(doc()), [])

    def test_forbidden_claims_are_caught_wherever_they_are(self) -> None:
        for text in (
            "Protects your privacy.",
            "All your traffic goes through the proxy.",
            "Works with every app.",
            "A system-wide VPN.",
            "Browse anonymously.",
            "Hides your IP address.",
            "Military-grade encryption.",
            "Encrypts everything.",
            "No-logs policy.",
            "Great for gaming.",
            "Guaranteed to block leaks.",
        ):
            with self.subTest(text=text):
                self.assertTrue(any("listing-full" in p for p in listing.check(doc(full=FULL + " " + text))), text)
                self.assertTrue(any("caption 2" in p for p in listing.check(doc(captions=("One", text)))), text)
                self.assertTrue(any("listing-short" in p for p in listing.check(doc(short=text))), text)
                self.assertTrue(any("listing-notes" in p for p in listing.check(doc(notes=text))), text)

    def test_a_denied_claim_is_allowed(self) -> None:
        self.assertEqual(listing.check(doc(full=FULL + " It does not hide your IP address from your proxy.")), [])
        self.assertEqual(listing.check(doc(full=FULL + " ProxyFlow is not a system-wide VPN.")), [])

    def test_a_denial_in_another_sentence_does_not_cover_the_claim(self) -> None:
        self.assertTrue(listing.check(doc(full=FULL + " Not a VPN. Hides your IP address.")))

    def test_a_denial_of_something_else_covers_nothing(self) -> None:
        for text in (
            "No account needed, and it hides your IP address.",
            "Not only fast: it works with every app.",
            "There is no limit: all your traffic goes through the proxy.",
            "Never slow, protects your privacy.",
            "Whether or not you pay, your traffic is protected.",
            "It is not a VPN service - browse anonymously.",
            "Not only does it hide your IP, it is fast.",
            "Not just a proxy, it hides your IP address.",
            "Not slow, protects your privacy.",
            "It is not slow and it hides your IP address.",
            "Not a VPN, hides your IP address.",
            "Does not need an account and hides your IP.",
            "Isn't it great that it protects you?",
            "It does not sell proxies and makes you anonymous.",
            "It is not slow because it hides your IP address.",
            "Not slow yet it protects your privacy.",
            "Never slow so it protects you.",
            "Not expensive since it hides your IP.",
            "Not one app hides your IP like ProxyFlow does.",
            "Isn't it great it protects you?",
            "Your IP address is hidden from every site you visit.",
            "Keeps your IP address hidden.",
            "Nobody can see your IP address.",
            "Websites never see your real IP address.",
            "Encrypts your connection.",
            "Encrypts your data end to end.",
            "Appear to be in another country.",
            "Unblock any website.",
            "Secure and private browsing.",
        ):
            with self.subTest(text=text):
                self.assertTrue(listing.check(doc(full=FULL + " " + text)), text)

    def test_ordinary_denials_are_allowed(self) -> None:
        for text in (
            "ProxyFlow cannot make you anonymous.",
            "ProxyFlow can't hide your IP address.",
            "It won't hide your IP address.",
            "It neither hides your IP address nor makes you anonymous.",
            "No guarantee of anything.",
            "There is no protection against a provider that logs.",
            "ProxyFlow is no system-wide VPN.",
            "It does not hide your IP address from your proxy.",
            "ProxyFlow is not a system-wide VPN.",
        ):
            with self.subTest(text=text):
                self.assertEqual(listing.check(doc(full=FULL + " " + text)), [], text)

    def test_plays_lengths_and_title_words(self) -> None:
        self.assertTrue(any("over Play's 30" in p for p in listing.check(doc(title="P" * 31))))
        self.assertTrue(any("over Play's 80" in p for p in listing.check(doc(short="s" * 81))))
        self.assertTrue(any("over Play's 4000" in p for p in listing.check(doc(full=FULL + " x" * 2000))))
        self.assertTrue(any("over Play's 500" in p for p in listing.check(doc(notes="n" * 501))))
        self.assertTrue(any("promotional" in p for p in listing.check(doc(title="ProxyFlow Free"))))
        self.assertTrue(any("promotional" in p for p in listing.check(doc(captions=("The best proxy app", "Two")))))
        self.assertTrue(any("promotional" in p for p in listing.check(doc(title="ProxyFlow - #1 Proxy"))))
        self.assertTrue(any("promotional" in p for p in listing.check(doc(captions=("#1 proxy app", "Two")))))
        self.assertTrue(any("promotional" in p for p in listing.check(doc(title="ProxyFlow No. 1"))))
        for title in ("ProxyFlow N°1", "ProxyFlow 1st", "ProxyFlow ＃1", "ProxyFlow ①", "The number-one proxy"):
            with self.subTest(title=title):
                self.assertTrue(any("promotional" in p for p in listing.check(doc(title=title))), title)
        self.assertTrue(any("emoji" in p for p in listing.check(doc(title="ProxyFlow 🚀"))))
        self.assertTrue(any("emoji" in p for p in listing.check(doc(title="ProxyFlow ⭐"))))
        self.assertTrue(any("emoji" in p for p in listing.check(doc(title="ProxyFlow 🇯🇵"))))
        self.assertTrue(any("emoji" in p for p in listing.check(doc(captions=("Fast ‼", "Two")))))
        self.assertTrue(any("emoji" in p for p in listing.check(doc(captions=("ProxyFlow™", "Two")))))
        self.assertEqual([p for p in listing.check(doc(title="ProxyFlow – proxy client")) if "emoji" in p], [])
        self.assertTrue(any("capitals" in p for p in listing.check(doc(title="PROXYFLOW"))))
        self.assertEqual(listing.check(doc(full=FULL + " It is free.")), [])

    def test_placeholders_and_missing_blocks(self) -> None:
        self.assertTrue(any("left to fill in" in p for p in listing.check(doc(short="Contact ‹e-mail›"))))
        self.assertTrue(any("no ```listing-notes" in p for p in listing.check(doc().replace("```listing-notes", "```text"))))
        self.assertEqual(listing.left_to_owner("| e-mail | ‹the owner's› |"), ["‹the owner's›"])
        self.assertEqual(listing.left_to_owner("| e-mail | `‹the owner's›` |"), ["‹the owner's›"])
        self.assertEqual(listing.left_to_owner("anything left to fill in (`‹…›`)"), [])

    def test_the_limitations_must_be_stated(self) -> None:
        for missing, text in (
            ("UDP blocked", FULL.replace("UDP is blocked", "UDP goes elsewhere")),
            ("ping", FULL.replace("ping is not answered", "ICMP is dropped")),
            ("not anonymous", FULL.replace("It does not make you anonymous.", "")),
            ("Android's VPN feature", FULL.replace("Android's VPN feature", "a tunnel")),
            ("no VPN server", FULL.replace("no VPN server", "nothing else")),
            ("what the proxy provider sees", FULL.replace("provider can see", "provider sees")),
        ):
            with self.subTest(missing=missing):
                self.assertIn(f"listing-full does not say: {missing}", listing.check(doc(full=text)))

    def test_two_to_eight_captions(self) -> None:
        self.assertTrue(any("two to eight" in p for p in listing.check(doc(captions=("One",)))))
        self.assertEqual(listing.check(doc(captions=tuple(f"Caption {i}" for i in range(8)))), [])


if __name__ == "__main__":
    unittest.main()
