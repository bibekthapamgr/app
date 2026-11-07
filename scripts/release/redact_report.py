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

PASSWORD_WORDS = r"pass(?:word|wd|phrase|code)?|pwd|pw|secret|token|api[_ -]?key|passwort|kennwort|mot de passe|contraseña|senha|パスワード|密码|密碼|비밀번호|पासवर्ड|пароль"
USER_WORDS = r"user(?:name)?|login|benutzer(?:name)?|usuario|ユーザー(?:名)?|用户名|사용자"
# A label may be wrapped in quotes, backticks or asterisks (JSON, Markdown, a table); the separator may be a colon of
# either width, an equals sign, a dash, an arrow, a bar, "is", or Japanese "は".
WRAP = r"[\"'`*_「」]*"
SEP = r"\s*(?:[:：=→|-]+\s*|は\s*)"
# "the password is hunter22" — "is" alone separates a password; not a username ("user is confused" is prose).
SEP_PASSWORD = r"\s*(?:[:：=→|-]+\s*(?:is\s*[:=]?\s*)?|は\s*|is\s*[:=]?\s*)"
VALUE = r"(\"[^\"\n]{1,200}\"|'[^'\n]{1,200}'|「[^」\n]{1,200}」|[^\s\"'|,;。。]{1,200})"


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


RULES: list[tuple[str, re.Pattern[str], object]] = [
    # scheme://user:pass@ — the user may hold an @ (an e-mail as a username): everything up to the last @ before the host.
    ("url credentials", re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)([^\s/@]+(?:@[^\s/@]+)*?)(:[^\s/@]*)?@(?=[^\s/@]+)"), r"\1<user>:<password>@"),
    ("bare credentials", re.compile(r"(?<![\w/:@.<])([^\s:@/\"'<]{1,64}):([^\s@\"'/]{1,200})@(?=[A-Za-z0-9\[])"), "<user>:<password>@"),
    ("curl credentials", re.compile(r"(?i)(?<![\w-])(-U|-u|--proxy-user|--user|--proxy-basic)\s+(\"[^\"]+\"|'[^']+'|\S+)"), r"\1 <user>:<password>"),
    ("basic auth header", re.compile(r"(?i)\b(Proxy-Authorization|Authorization)\s*:\s*(Basic|Bearer|Digest|Negotiate|NTLM)\s+\S+"), r"\1: \2 <redacted>"),
    ("password field", re.compile(rf"(?i)({WRAP}(?:{PASSWORD_WORDS}){WRAP}{SEP_PASSWORD}){VALUE}"), _value_sub("<redacted>", 1)),
    ("username field", re.compile(rf"(?i)({WRAP}(?:{USER_WORDS}){WRAP}{SEP}){VALUE}"), _value_sub("<user>", 1)),
    # curl -v: "* Proxy auth using Basic with user 'bibek'"
    ("username field", re.compile(rf"(?i)\b((?:{USER_WORDS})\s+)([\"'][^\"'\n]{{1,200}}[\"'])"), _value_sub("'<user>'", 1)),
    ("e-mail address", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"), "<email>"),
    # An IPv6 address has "::" or seven colons; a time (10:16:30) and a MAC (00:1A:2B:3C:4D:5E, five) do not.
    ("IPv6 address", re.compile(r"(?<![\w:.])(?:(?=[0-9A-Fa-f:]*::)|(?=(?:[0-9A-Fa-f]{0,4}:){7}))(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{0,4}|(?:\d{1,3}\.){3}\d{1,3})(?:%\w+)?(?![\w:])"), "<ipv6>"),
    ("IPv4 address", re.compile(r"(?<![\w.])(?<!version )(?<!UI )(?<!Android )(?<!iOS )(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\w.])(:\d{1,5})?"), _ip_sub),
    ("long token", re.compile(r"(?<![A-Za-z0-9/+=])(?=[A-Za-z0-9/+=_-]*[0-9])(?=[A-Za-z0-9/+=_-]*[A-Za-z])[A-Za-z0-9/+=_-]{32,}(?![A-Za-z0-9/+=])"), "<token>"),
]
HOST = re.compile(
    r"(?<![\w-])((?:xn--[a-z0-9-]+\.|[\w](?:[\w-]{0,61}[\w])?\.)+(?:[a-z]{2,24}|xn--[a-z0-9-]+|test|local|lan|internal|home))(:\d{1,5})?(?!\w|-|\.\w)",
    re.I,
)
ONE_WORD_HOST = re.compile(r"(?<![\w.:/-])([a-z][\w-]{1,62}):(\d{2,5})(?![\w.])", re.I)
CODE_FILE = re.compile(r"\.(?:kt|kts|java|py|go|md|xml|json|txt|log|yml|yaml|sh|gradle)$", re.I)


def is_code_name(name: str) -> bool:
    """A Java name (app.proxyflow.android, kotlinx.coroutines.JobCancellationException), a file name (Engine.kt), a
    version-like number: not a host. A host's labels are lower case and its last label is a known ending."""
    labels = name.split(".")
    if CODE_FILE.search(name):
        return True
    if any(label[:1].isupper() for label in labels):
        return True
    if labels[0] in ("app", "androidx", "android", "kotlin", "kotlinx", "java", "javax") and labels[-1].lower() not in ("net", "io", "jp", "com", "org", "co"):
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
