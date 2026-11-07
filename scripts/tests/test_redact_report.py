"""Tests of redact_report.py: every form a user pastes a credential or an address in is replaced, the app's own names
are kept, and ordinary report text survives.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
import redact_report as rr  # noqa: E402


class Redaction(unittest.TestCase):
    def assertGone(self, text: str, *secrets: str) -> str:
        out, counts = rr.redact(text)
        for secret in secrets:
            self.assertNotIn(secret, out, f"{secret!r} survived in {out!r}")
        self.assertTrue(counts, "nothing was counted")
        return out

    def test_proxy_urls(self) -> None:
        out = self.assertGone("my proxy is socks5://bibek:hunter22@proxy.example.net:1080 ok", "bibek", "hunter22", "proxy.example.net")
        self.assertIn("socks5://<user>:<password>@<host>:1080", out)
        self.assertGone("http://bibek@proxy.example.net:3128", "bibek", "proxy.example.net")

    def test_password_fields(self) -> None:
        for text in ("Password: hunter22", "password=hunter22", 'pass: "hunter 22"', "the password is hunter22", "PWD hunter22", "token: abc"):
            with self.subTest(text=text):
                out = self.assertGone(text, "hunter22" if "hunter" in text else "abc")
                self.assertIn("<redacted>", out)
        out = self.assertGone("Username: bibek", "bibek")
        self.assertIn("<user>", out)

    def test_headers_mail_addresses_tokens(self) -> None:
        self.assertGone("Proxy-Authorization: Basic YmliZWs6aHVudGVyMjI=", "YmliZWs6aHVudGVyMjI=")
        self.assertGone("mail me at bibek@example.com", "bibek@example.com")
        self.assertGone("server 203.0.113.9:1080 refused", "203.0.113.9")
        self.assertGone("v6 2001:db8::1 refused", "2001:db8::1")
        self.assertGone("key a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6")

    def test_hosts_but_not_the_apps_own(self) -> None:
        out, counts = rr.redact("tunnel to proxyflow.test and connectivitycheck.gstatic.com, then jp1.myproxy.co.jp:443 and corp.internal")
        self.assertIn("proxyflow.test", out)
        self.assertIn("connectivitycheck.gstatic.com", out)
        self.assertNotIn("myproxy", out)
        self.assertNotIn("corp.internal", out)
        self.assertEqual(counts["host name"], 2)

    def test_ordinary_text_is_kept(self) -> None:
        text = "Version 1.0.0 (11), Android 15. Tapped Connect; it said Reconnecting after 30 s. Logs: E07 proxy refused. app.proxyflow.android"
        out, counts = rr.redact(text)
        self.assertEqual(out, text, out)
        self.assertEqual(counts, {})

    def test_the_apps_markers_stay(self) -> None:
        out, _ = rr.redact("server <redacted> user <user>")
        self.assertEqual(out, "server <redacted> user <user>")


if __name__ == "__main__":
    unittest.main()
