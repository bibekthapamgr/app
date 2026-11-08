"""Tests of redact_report.py: every form a user pastes a credential or an address in is replaced (reviews A-1 to A-9
of M12), the app's own names and export lines survive, and ordinary prose about passwords is not destroyed.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
import redact_report as rr  # noqa: E402


class Redaction(unittest.TestCase):
    def gone(self, text: str, *secrets: str) -> str:
        out, counts = rr.redact(text)
        for secret in secrets:
            self.assertNotIn(secret, out, f"{secret!r} survived in {out!r}")
        self.assertTrue(counts, f"nothing was counted for {text!r}")
        return out

    def kept(self, text: str) -> None:
        out, counts = rr.redact(text)
        self.assertEqual(out, text, f"{text!r} became {out!r} ({dict(counts)})")

    def test_proxy_urls_with_and_without_credentials(self) -> None:
        out = self.gone("my proxy is socks5://bibek:hunter22@proxy.example.net:1080 ok", "bibek", "hunter22", "proxy.example.net")
        self.assertIn("socks5://<user>:<password>@<host>:1080", out)
        self.assertIn("<host>:1080", self.gone("socks5://proxy.example.net:1080", "proxy.example.net"))
        self.gone("https://proxy.example.net/path", "proxy.example.net")
        self.assertIn("http://<ip>:3128", self.gone("http://203.0.113.9:3128", "203.0.113.9"))
        self.gone("socks5://bibek@example.com:hunter22@jp.myproxy.net:1080", "bibek", "hunter22", "myproxy")
        self.gone("http://bibek@proxy.example.net:3128", "bibek", "proxy.example.net")

    def test_bare_and_curl_credentials(self) -> None:
        self.gone("bibek:hunter22@proxy.example.net:1080", "bibek", "hunter22", "proxy.example.net")
        self.gone("curl -v -U bibek:hunter22 --proxy myproxy.jp:3128", "bibek", "hunter22", "myproxy.jp")
        self.gone("curl --proxy-user 'bibek:hunter 22'", "bibek", "hunter 22")
        self.gone("curl -Ubibek:hunter22 --proxy myproxy.jp:3128", "bibek", "hunter22")
        out, counts = rr.redact("curl --proxy-user=bibek:hunter22 http://x")
        self.assertEqual(out, "curl --proxy-user <user>:<password> http://x")
        self.assertIn("curl credentials", counts)
        self.gone("* Basic YmliZWs6aHVudGVyMjI= sent", "YmliZWs6aHVudGVyMjI=")
        self.kept("Basic authentication is used")

    def test_password_fields_in_every_form(self) -> None:
        for text, secret in (
            ("Password: hunter22", "hunter22"),
            ("password=hunter22", "hunter22"),
            ('pass: "hunter 22"', "hunter 22"),
            ("the password is hunter22", "hunter22"),
            ("password is: hunter22", "hunter22"),
            ("password - hunter22", "hunter22"),
            ("password → hunter22", "hunter22"),
            ('{"password": "hunter22", "username": "bibek"}', "hunter22"),
            ('{"password":"hunter22"}', "hunter22"),
            ("**password**: hunter22", "hunter22"),
            ("`password`: hunter22", "hunter22"),
            ("| password | hunter22 |", "hunter22"),
            ("pw: hunter22", "hunter22"),
            ("pw=hunter22", "hunter22"),
            ("passcode: hunter22", "hunter22"),
            ("token: abc", "abc"),
            ("パスワード: hunter22", "hunter22"),
            ("パスワード：hunter22", "hunter22"),
            ("パスワードはhunter22です", "hunter22"),
            ("Passwort: hunter22", "hunter22"),
            ("密码: hunter22", "hunter22"),
            ("비밀번호: hunter22", "hunter22"),
            ("password：hunter22", "hunter22"),
            # Re-review A RA-1: the colon inside the bold, HTML labels.
            ("**Password:** hunter22", "hunter22"),
            ("- **Proxy password:** hunter22", "hunter22"),
            ("Password:** hunter22", "hunter22"),
            ("<b>password</b>: hunter22", "hunter22"),
            ("password:</b> hunter22", "hunter22"),
            # RA-4, RA-5: 「」 as the only separator; auth/creds labels.
            ("パスワード「hunter22」", "hunter22"),
            ("auth: bibek:hunter22", "hunter22"),
            ("creds bibek:hunter22", "hunter22"),
            ("Password    hunter22", "hunter22"),
            ("credentials: bibek/hunter22", "hunter22"),
            # Re-review C RC-3, RC-9: a pair with a slash between spaces; an XML element; the arrow separators.
            ("credentials: bibek / hunter22", "hunter22"),
            ("login details: bibek / hunter22", "hunter22"),
            ("<password>hunter22</password>", "hunter22"),
            ("password => hunter22", "hunter22"),
            ("password -> hunter22", "hunter22"),
            ("pass hunter22", "hunter22"),
            ("* Basic YmliZWs6aHVudGVyMjI= sent", "YmliZWs6aHVudGVyMjI="),
            ("basic YmliZWs6aHVudGVyMjI= sent", "YmliZWs6aHVudGVyMjI="),
        ):
            with self.subTest(text=text):
                out = self.gone(text, secret)
                self.assertIn("<redacted>", out)
        for text in ("Username: bibek", '{"username": "bibek"}', "ユーザー名: bibek", "login=bibek"):
            with self.subTest(text=text):
                self.assertIn("<user>", self.gone(text, "bibek"))

    def test_headers_mail_addresses_tokens(self) -> None:
        self.gone("Proxy-Authorization: Basic YmliZWs6aHVudGVyMjI=", "YmliZWs6aHVudGVyMjI=")
        self.gone("* Proxy auth using Basic with user 'bibek'", "bibek")
        self.gone("mail me at bibek@example.com", "bibek@example.com")
        self.assertIn("<ip>:1080", self.gone("server 203.0.113.9:1080 refused", "203.0.113.9"))
        self.gone("v6 2001:db8::1 refused", "2001:db8::1")
        self.gone("v6 2001:0db8:85a3:0000:0000:8a2e:0370:7334 ok", "2001:0db8:85a3")
        self.gone("via ::ffff:203.0.113.9", "203.0.113.9")
        self.gone("key a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6")
        # RA-3: an address at the end of a sentence.
        self.gone("my gateway is 192.168.1.1. and the proxy is 203.0.113.9. ok", "192.168.1.1", "203.0.113.9")
        self.gone("ip 203.0.113.9.", "203.0.113.9")

    def test_hosts_in_every_place_but_not_the_apps_own(self) -> None:
        out, counts = rr.redact("tunnel to proxyflow.test and connectivitycheck.gstatic.com, then jp1.myproxy.co.jp:443 and corp.internal")
        self.assertIn("proxyflow.test", out)
        self.assertIn("connectivitycheck.gstatic.com", out)
        self.assertNotIn("myproxy", out)
        self.assertNotIn("corp.internal", out)
        self.assertEqual(counts["host name"], 2)
        self.gone("my server is proxy.example.net.", "proxy.example.net")
        self.gone("try proxy_1.example.net", "proxy_1.example.net")
        self.gone("the proxy myproxy:1080 refused", "myproxy:1080")
        self.gone("host com.myprovider.proxy", "myprovider")
        self.gone("host io.myproxy.net", "myproxy")
        self.gone("at xn--80ahd1agd.xn--p1ai", "xn--80ahd1agd")
        # RA-2: hosts typed as the provider prints them, and common first labels.
        self.gone("My server is Proxy.MyProvider.com and MyProxy.jp and JP1.MyProxy.Net:1080", "MyProvider", "MyProxy")
        self.gone("Host=JP-1.MyProxy.jp", "MyProxy")
        self.gone("the proxy is at MYPROXY.EXAMPLE.NET", "MYPROXY.EXAMPLE")
        self.gone("host app.proxyprovider.de and android.myproxy.fr and app.proxy.me", "proxyprovider", "myproxy", "app.proxy.me")
        # RA-10: internationalised names.
        self.gone("сервер пример.рф и proxy.рф", "пример.рф", "proxy.рф")
        self.gone("サーバーはプロキシ.日本です", "プロキシ.日本")
        self.kept("jp2.proxyflow.test answered")

    def test_the_exported_log_and_ordinary_report_text_survive(self) -> None:
        for text in (
            "Version 1.0.0 (11), Android 15. Tapped Connect; it said Reconnecting after 30 s. Logs: E07 proxy refused. app.proxyflow.android",
            "Exported 2026-11-08 10:16:30.250 +09:00",
            "2026-11-08 10:15:02.120 +09:00  ERROR  stage=connect code=AUTH_FAILED detail=[redacted]",
            "MAC 00:1A:2B:3C:4D:5E at 10:16:30",
            "the password field was empty",
            "The password prompt appears twice",
            "Bearer token support is missing",
            "user is confused and login is slow",
            "Password: [redacted] and username=[redacted]",
            "Android 14.0.0.1 on One UI 6.1.1.1, version 1.0.0.1",
            "kotlinx.coroutines.JobCancellationException at Engine.kt:123 (settings.gradle.kts, README.md)",
            "Wi-Fi. Then it reconnected",
            "server <redacted> user <user>",
            "androidx.compose.ui.platform.AndroidComposeView",
            "dalvik.system.NativeStart.main at okhttp3.internal.http2.Http2Connection",
            # RA-6: hyphenated prose, and a label at the end of a line is not a label for the next line.
            "a password-protected proxy with token-based auth",
            "user-agent: Mozilla/5.0",
            "| password |\n26 attempts",
            # RA-11: version-like numbers of other makers, a path, a commit hash, file names.
            "MIUI 14.0.0.1 and HyperOS 2.0.0.1, build 240.0.0.1",
            "/storage/emulated/0/Download/ProxyFlow-2026-11-08-101630-export.txt",
            "commit 3f8c3ad39c0e5b1d2a4f6c8e0b2d4f6a8c0e2b4d",
            "node.js, proxy.pac and settings.html",
            "python3 -u run.py",
            "attempt:10 and Reconnecting:30",
            "| username | password |",
            # RC-1, RC-2: a value after spaces alone must look like a secret; "Basic" starts sentences.
            "The password  was wrong.",
            "I typed the password    twice",
            "the auth  token expired",
            "Password  (the same as on the website)",
            "Proxy auth 10:16:30 failed, retried at 10:17",
            "bypass  enabled",
            "password 2 times, changed on 2026-11-08",
            "reason=auth  profile=x",
            "Basic connectivity check failed",
            "Basic troubleshooting done, Basic 12345678 plan",
        ):
            with self.subTest(text=text):
                self.kept(text)

    def test_the_rotate_line_only_for_a_real_credential(self) -> None:
        for text in (
            "Password: [redacted]",
            "a password-protected proxy",
            "the password field was empty",
            "The password  was wrong.",
            "Proxy auth 10:16:30 failed",
            "Basic connectivity check failed",
        ):
            _, counts = rr.redact(text)
            self.assertFalse(any(k in counts for k in rr.CREDENTIAL_KINDS), text)
        _, counts = rr.redact("Password: hunter22")
        self.assertTrue(any(k in counts for k in rr.CREDENTIAL_KINDS))


if __name__ == "__main__":
    unittest.main()
