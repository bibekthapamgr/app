#!/usr/bin/env python3
"""Redacts what a bug report must not carry before it is kept, quoted or shared (M12): passwords and usernames in any
form a user might paste them (proxy URLs, "password: …", key=value lines, Basic auth headers), e-mail addresses, IPv4 and
IPv6 addresses, host names, long tokens, and the app's own `<redacted>` markers left as they are. The in-app log export
already removes these; this is for what users type beside it.

    scripts/release/redact_report.py < report.txt > redacted.txt       (stdin to stdout)
    scripts/release/redact_report.py report.txt [-o redacted.txt]      (a file)

Prints a count per kind on stderr, so the owner knows what was in the report (and can ask the reporter to rotate a
password that was). Host names of Android, Google and this project (`proxyflow.test`, `connectivitycheck.gstatic.com`)
are kept, since they are the app's own and tell the reader something; every other name becomes `<host>`.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter

KEEP_HOSTS = {
    "connectivitycheck.gstatic.com",
    "play.google.com",
    "developer.android.com",
    "support.google.com",
    "github.com",
    "proxyflow.test",
    "localhost",
    "example.com",
    "example.org",
    "example.net",
}

RULES: list[tuple[str, re.Pattern[str], str]] = [
    # socks5://user:pass@host:1080, http://user:pass@host — the credentials first, the host later by the host rule.
    ("url credentials", re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)([^\s/@:]+)(:[^\s/@]*)?@"), r"\1<user>:<password>@"),
    ("basic auth header", re.compile(r"(?i)\b(Proxy-Authorization|Authorization)\s*:\s*(Basic|Bearer|Digest)\s+\S+"), r"\1: \2 <redacted>"),
    (
        "password field",
        re.compile(r"(?i)\b(pass(?:word|wd|phrase)?|pwd|secret|token|api[_ -]?key)\b(\s*[:=]\s*|\s+is\s+|\s+)(\"[^\"]{1,200}\"|'[^']{1,200}'|\S{1,200})"),
        r"\1\2<redacted>",
    ),
    ("username field", re.compile(r"(?i)\b(user(?:name)?|login)\b(\s*[:=]\s*|\s+is\s+)(\"[^\"]{1,200}\"|'[^']{1,200}'|\S{1,200})"), r"\1\2<user>"),
    ("e-mail address", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"), "<email>"),
    ("IPv6 address", re.compile(r"(?<![\w:.])(?=[0-9A-Fa-f:]*[0-9A-Fa-f])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\w:])"), "<ipv6>"),
    ("IPv4 address", re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b"), "<ip>"),
    ("long token", re.compile(r"(?<![A-Za-z0-9/+=])(?=[A-Za-z0-9/+=_-]*[0-9])(?=[A-Za-z0-9/+=_-]*[A-Za-z])[A-Za-z0-9/+=_-]{32,}(?![A-Za-z0-9/+=])"), "<token>"),
]
HOST = re.compile(r"(?<![\w./-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,24}|test|local|lan|internal|home))(?::\d{1,5})?(?![\w.-])", re.I)
# A Java package or class name (app.proxyflow.android, androidx.compose.ui.Foo) is not a host.
PACKAGE_PREFIXES = ("app.", "com.", "org.", "io.", "net.", "androidx.", "android.", "kotlin.", "java.", "javax.")


def redact_hosts(text: str, counts: Counter) -> str:
    def swap(m: re.Match[str]) -> str:
        name = m.group(1).lower()
        if name in KEEP_HOSTS or any(name.endswith("." + keep) for keep in ("android.com", "google.com", "gstatic.com", "googleapis.com", "github.com")):
            return m.group(0)
        if name.startswith(PACKAGE_PREFIXES) and ":" not in m.group(0):
            return m.group(0)
        counts["host name"] += 1
        return "<host>" + (m.group(0)[len(m.group(1)) :] if ":" in m.group(0)[len(m.group(1)) :] else "")

    return HOST.sub(swap, text)


def redact(text: str) -> tuple[str, Counter]:
    counts: Counter = Counter()
    for kind, pattern, replacement in RULES:
        text, n = pattern.subn(replacement, text)
        if n:
            counts[kind] += n
    text = redact_hosts(text, counts)
    return text, counts


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
        print("redacted: " + ", ".join(f"{n} {kind}{'s' if n > 1 else ''}" for kind, n in sorted(counts.items())), file=sys.stderr)
        if any(k in counts for k in ("url credentials", "password field", "basic auth header")):
            print("a password was in the report: ask the reporter to change it at their provider", file=sys.stderr)
    else:
        print("nothing to redact", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
