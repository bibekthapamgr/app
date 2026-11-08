#!/usr/bin/env python3
"""Redacts what a bug report must not carry before it is kept, quoted or shared (M12): passwords and usernames in any
form a user might paste them (proxy URLs, "password: …", key=value, JSON, Markdown, tables, curl's -U, Basic auth
headers, bare user:password@host, labels in other languages), e-mail addresses, IPv4 and IPv6 addresses, host names
and long tokens. The app's own export markers ([redacted], [host], [url], [address]) are left as they are, and the
export's own lines (timestamps, catalogue codes, version numbers, package and class names) survive.

    scripts/release/redact_report.py < report.txt > redacted.txt       (stdin to stdout)
    scripts/release/redact_report.py report.txt [-o redacted.txt]      (a file)

Prints a count per kind on stderr, so the owner knows what was in the report (and asks the reporter to rotate a
password that was). Host names of Android, Google, GitHub and this project are kept, since they are the app's own
and tell the reader something; every other name becomes <host>. The rules prefer to take too much: a word after
"password:" that was not a password is a small loss beside a password kept.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter

KEEP_HOSTS = {"localhost", "proxyflow.test", "example.com", "example.org", "example.net"}
KEEP_SUFFIXES = ("android.com", "google.com", "gstatic.com", "googleapis.com", "github.com", "proxyflow.test")
MARKERS = re.compile(r"^(?:<(?:redacted|user|password|host|ip|ipv6|email|token|url)>|\[(?:redacted|host|url|address)\])$")

PASSWORD_WORDS = r"pass(?:word|wd|phrase|code)?|pwd|pw|secret|token|api[_ -]?key|auth|creds?|credentials|login details|ログイン情報|passwort|kennwort|mot de passe|contraseña|senha|パスワード|密码|密碼|비밀번호|पासवर्ड|пароль"
USER_WORDS = r"user(?:name)?|login|benutzer(?:name)?|usuario|ユーザー(?:名)?|用户名|사용자"
# A label may be wrapped in quotes, backticks, asterisks or a tag (JSON, Markdown, a table, HTML), before and — for
# the asterisks, underscores, backticks and tags — after the separator ("**Password:** x"); the separator may be a colon
# of either width, an equals sign, an arrow, a bar, a dash between spaces (never the hyphen of "password-protected"),
# "is", or Japanese "は"; it stays on one line (a table header is not a label for the next line's first word).
WRAP = r"(?:[\"'`*_「」]|</?\w+>)*"
AFTER_SEP = r"(?:[`*_]+|</?\w+>)*[ \t]*"
PUNCT_SEP = r"(?:->|=>|[:：=→|]+)"
SEP = rf"[ \t]*(?:{PUNCT_SEP}[ \t]*|[ \t]+-[ \t]+|は[ \t]*){AFTER_SEP}"
# A value after nothing but spaces ("pass hunter22", "creds bibek:hunter22") must look like a secret — six or more
# characters with a digit, a symbol, a colon or a slash, not a time, a date or a URL — so that "the password  was
# wrong", "Proxy auth 10:16:30 failed" and "the auth token expired" stay prose (re-review C, RC-1).
SECRET_SHAPED = r"(?!\d{1,2}:\d{2}(?::\d{2})?\b)(?!\d{4}-\d\d-\d\d\b)(?![a-z][a-z0-9+.-]*://)(?=\S{6,})(?=\S*[\d!@#$%^&*+~:/])"
# "the password is hunter22" — "is" alone separates a password; not a username ("user is confused" is prose); a
# 「…」 value needs no separator at all (パスワード「x」); an XML element (<password>x</password>) is its own rule.
SEP_PASSWORD = rf"[ \t]*(?:{PUNCT_SEP}[ \t]*(?:is[ \t]*[:=]?[ \t]*)?|[ \t]+-[ \t]+|は[ \t]*|is[ \t]*[:=]?[ \t]*|(?=「)|[ \t]+{SECRET_SHAPED}){AFTER_SEP}"
VALUE = rf"(?![`*_]+(?:\s|$))(?!(?:{PASSWORD_WORDS}|{USER_WORDS})\b[ \t]*(?:[|:：=]|$))(\"[^\"\n]{{1,200}}\"|'[^'\n]{{1,200}}'|「[^」\n]{{1,200}}」|[^\s\"'|,;。。<]{{1,200}})"
# "credentials: bibek / hunter22" — a pair with a slash between spaces goes as one value (RC-3).
PAIR_VALUE = r"([^\s\"'|,;。。<]{1,64}[ \t]*/[ \t]*[^\s\"'|,;。。<]{1,200})"


def _value_sub(kind: str, prefix_groups: int):
    def swap(m: re.Match[str], counts: Counter, rule: str) -> str:
        value = m.group(prefix_groups + 1)
        if MARKERS.match(value.strip("\"'「」")):
            return m.group(0)  # already redacted (the app's export, or this script's earlier run): not counted
        counts[rule] += 1
        return "".join(m.group(i) or "" for i in range(1, prefix_groups + 1)) + kind

    return swap


def _ip_sub(m: re.Match[str], counts: Counter, rule: str) -> str:
    counts[rule] += 1
    return "<ip>" + (m.group(1) or "")


def _token_sub(m: re.Match[str], counts: Counter, rule: str) -> str:
    token = m.group(0)
    if len(token) in (40, 64) and all(c in "0123456789abcdefABCDEF" for c in token):
        return token  # a commit hash or a SHA-256, as the app's own release facts and CI print them
    counts[rule] += 1
    return "<token>"


def _curl_sub(m: re.Match[str], counts: Counter, rule: str) -> str:
    counts[rule] += 1
    return f"{m.group(1) or m.group(2)} <user>:<password>"


RULES: list[tuple[str, re.Pattern[str], object]] = [
    # scheme://user:pass@ — the user may hold an @ (an e-mail as a username): everything up to the last @ before the host.
    ("url credentials", re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)([^\s/@]+(?:@[^\s/@]+)*?)(:[^\s/@]*)?@(?=[^\s/@]+)"), r"\1<user>:<password>@"),
    ("bare credentials", re.compile(r"(?<![\w/:@.<])([^\s:@/\"'<]{1,64}):([^\s@\"'/]{1,200})@(?=[A-Za-z0-9\[])"), "<user>:<password>@"),
    ("curl credentials", re.compile(r"(?i)(?<![\w-])(?:(-U|-u)[ \t]*|(--proxy-user|--user|--proxy-basic)(?:\s+|=))(\"[^\"]+\"|'[^']+'|\S*:\S*)"), _curl_sub),
    ("basic auth header", re.compile(r"(?i)\b(Proxy-Authorization|Authorization)\s*:\s*(Basic|Bearer|Digest|Negotiate|NTLM)\s+\S+"), r"\1: \2 <redacted>"),
    # "Basic YmliZWs6…" as a debugging tool or logcat prints it, without the header's name ("Basic authentication" is prose).
    # The token must be base64-shaped (twelve or more characters with a digit, "+", "/" or "="): "Basic connectivity
    # check failed" and "Basic troubleshooting done" are sentences (RC-2).
    ("basic auth header", re.compile(r"(?i)\b(Basic|Bearer)[ \t]+(?!auth)(?=[A-Za-z0-9+/=._~-]*[\d+/=])([A-Za-z0-9+/=._~-]{12,})(?![\w+/=])"), r"\1 <redacted>"),
    # An exported XML configuration: <password>hunter22</password> (RC-9).
    ("password field", re.compile(rf"(?i)(<(?:{PASSWORD_WORDS})>)([^<\n]{{1,200}})(?=</)"), _value_sub("<redacted>", 1)),
    ("password field", re.compile(rf"(?i)({WRAP}(?:{PASSWORD_WORDS}){WRAP}{SEP_PASSWORD}){PAIR_VALUE}"), _value_sub("<redacted>", 1)),
    ("password field", re.compile(rf"(?i)({WRAP}(?:{PASSWORD_WORDS}){WRAP}{SEP_PASSWORD}){VALUE}"), _value_sub("<redacted>", 1)),
    ("username field", re.compile(rf"(?i)({WRAP}(?:{USER_WORDS}){WRAP}{SEP}){VALUE}"), _value_sub("<user>", 1)),
    # curl -v: "* Proxy auth using Basic with user 'bibek'"
    ("username field", re.compile(rf"(?i)\b((?:{USER_WORDS})\s+)([\"'][^\"'\n]{{1,200}}[\"'])"), _value_sub("'<user>'", 1)),
    ("e-mail address", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"), "<email>"),
    # An IPv6 address has "::" or seven colons; a time (10:16:30) and a MAC (00:1A:2B:3C:4D:5E, five) do not.
    ("IPv6 address", re.compile(r"(?<![\w:.])(?:(?=[0-9A-Fa-f:]*::)|(?=(?:[0-9A-Fa-f]{0,4}:){7}))(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{0,4}|(?:\d{1,3}\.){3}\d{1,3})(?:%\w+)?(?![\w:])"), "<ipv6>"),
    # A first octet above 223 is multicast or reserved, never a proxy: "MIUI 14.0.0.1" and "build 240.0.0.1" survive;
    # a trailing full stop does not save an address (only a dot followed by a digit continues it).
    ("IPv4 address", re.compile(r"(?<![\w.])(?<!version )(?<!UI )(?<!Android )(?<!iOS )(?<!MIUI )(?<!HyperOS )(?<!build )(?:(?:1?\d?\d|2[01]\d|22[0-3])\.)(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){2}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?!\w|\.\d)(:\d{1,5})?"), _ip_sub),
    # A long mixed run is a token; a path (a run that starts with "/") is not.
    ("long token", re.compile(r"(?<![A-Za-z0-9/+=])(?!/)(?=[A-Za-z0-9/+=_-]*[0-9])(?=[A-Za-z0-9/+=_-]*[A-Za-z])[A-Za-z0-9/+=_-]{32,}(?![A-Za-z0-9/+=])"), _token_sub),
]
# \w is Unicode-aware in Python, so an internationalised name (пример.рф, プロキシ.日本) is a host too; the last label
# is letters of any script, an IDN "xn--" label, or a private ending.
HOST = re.compile(
    r"(?<![\w-])((?:xn--[a-z0-9-]+\.|[\w](?:[\w-]{0,61}[\w])?\.)+(?:[^\W\d_]{2,24}|xn--[a-z0-9-]+|test|local|lan|internal|home))(:\d{1,5})?(?!\w|-|\.\w)",
    re.I,
)
# A bare name with a proxy-like port (myproxy:1080); "attempt:10" in prose is not one.
ONE_WORD_HOST = re.compile(r"(?<![\w.:/-])([a-z][\w-]{1,62}):(\d{4,5}|80|443)(?![\w.])", re.I)
CODE_FILE = re.compile(r"\.(?:kt|kts|java|py|go|md|xml|json|txt|log|yml|yaml|sh|gradle|js|ts|css|html|pac|apk|aab|aar|so|jar|zip|png|jpg|pem|p12|jks)$", re.I)
# Package roots no proxy host starts with; "app." and "android." are common host labels, so only app.proxyflow.* counts.
CODE_ROOTS = {"androidx", "kotlinx", "kotlin", "java", "javax", "dalvik", "okhttp3", "okio", "org"}
# Generic top-level labels; a two-letter last label is a country code. A name ending in one of these is a host even
# when a label is capitalised (Proxy.MyProvider.com); a class name's last label never is one.
GTLDS = {
    "com", "net", "org", "io", "info", "biz", "app", "dev", "xyz", "online", "site", "tech", "cloud", "host", "pro",
    "shop", "store", "top", "club", "asia", "eu", "name", "mobi", "vpn", "proxy", "network", "link", "live", "space",
    "me", "tv", "cc", "ws", "to", "one", "zone", "world", "today", "run", "fun", "icu", "digital", "systems",
    "services", "solutions", "center", "ltd", "inc", "llc", "test", "local", "lan", "internal", "home",
}


def tld_like(label: str) -> bool:
    label = label.lower()
    return len(label) == 2 or label in GTLDS or label.startswith("xn--")


def is_code_name(name: str) -> bool:
    """A Java name (app.proxyflow.android, kotlinx.coroutines.JobCancellationException), a file name (Engine.kt): not
    a host. A class has a capitalised label and never ends in a top-level domain; a host may be typed in any case."""
    labels = name.split(".")
    if CODE_FILE.search(name):
        return True
    if labels[0].lower() in CODE_ROOTS and not tld_like(labels[-1]):
        return True
    if name.lower().startswith("app.proxyflow."):
        return True
    if any(label[:1].isupper() for label in labels) and not tld_like(labels[-1]):
        return True
    return False


def redact_hosts(text: str, counts: Counter) -> str:
    def swap(m: re.Match[str]) -> str:
        name = m.group(1).lower()
        if name in KEEP_HOSTS or any(name == keep or name.endswith("." + keep) for keep in KEEP_SUFFIXES):
            return m.group(0)
        if is_code_name(m.group(1)):
            return m.group(0)
        counts["host name"] += 1
        return "<host>" + (m.group(2) or "")

    text = HOST.sub(swap, text)

    def swap_one(m: re.Match[str]) -> str:
        if m.group(1).lower() in ("localhost", "port", "line", "api", "android", "version", "code", "error", "at"):
            return m.group(0)
        counts["host name"] += 1
        return f"<host>:{m.group(2)}"

    return ONE_WORD_HOST.sub(swap_one, text)


def redact(text: str) -> tuple[str, Counter]:
    counts: Counter = Counter()
    for kind, pattern, replacement in RULES:
        if callable(replacement):
            text = pattern.sub(lambda m, r=replacement, k=kind: r(m, counts, k), text)
        else:
            text, n = pattern.subn(replacement, text)
            if n:
                counts[kind] += n
    text = redact_hosts(text, counts)
    return text, counts


CREDENTIAL_KINDS = ("url credentials", "bare credentials", "curl credentials", "password field", "basic auth header")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", nargs="?", help="the report; stdin when absent")
    parser.add_argument("-o", "--out", help="where to write; stdout when absent")
    args = parser.parse_args(argv)
    text = open(args.file, encoding="utf-8", errors="replace").read() if args.file else sys.stdin.read()
    out, counts = redact(text)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    else:
        sys.stdout.write(out)
    if counts:
        print("redacted: " + ", ".join(f"{n} × {kind}" for kind, n in sorted(counts.items())), file=sys.stderr)
        if any(k in counts for k in CREDENTIAL_KINDS):
            print("a password was in the report: ask the reporter to change it at their provider", file=sys.stderr)
    else:
        print("nothing to redact", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
