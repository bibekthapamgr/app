#!/usr/bin/env python3
"""Writes docs/m9/privacy-policy.md, the privacy policy for the public address Google Play asks for (owner decision D9),
from the app's own text (M9): the strings in feature/about/src/main/res/values/strings.xml, in the order the in-app page
shows them (PRIVACY_SECTIONS in PrivacyPolicyScreen.kt), for the version in app/build.gradle.kts. The two can't say
different things: scripts/tests/test_privacy_policy.py fails in CI when the document is not what this writes.

Usage: privacy_policy.py [--out FILE]   (default: print it)
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
STRINGS = os.path.join(ROOT, "feature", "about", "src", "main", "res", "values", "strings.xml")
SCREEN = os.path.join(ROOT, "feature", "about", "src", "main", "kotlin", "app", "proxyflow", "feature", "about", "PrivacyPolicyScreen.kt")
BUILD = os.path.join(ROOT, "app", "build.gradle.kts")
DOCUMENT = os.path.join(ROOT, "docs", "m9", "privacy-policy.md")


def android_text(raw: str) -> str:
    """What Android shows for a resource string: its escapes decoded."""
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m.group(1)[1:], 16)) if len(m.group(1)) == 5 else {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


def strings() -> dict[str, str]:
    root = ElementTree.parse(STRINGS).getroot()
    return {e.get("name", ""): android_text("".join(e.itertext())) for e in root.findall("string")}


def sections() -> list[tuple[str, list[str]]]:
    """(title, paragraphs) string names of each section, in the screen's order."""
    with open(SCREEN, encoding="utf-8") as f:
        source = f.read()
    listing = source[source.index("PRIVACY_SECTIONS") :]
    found = []
    for block in listing.split("DocumentSection(")[1:]:
        title = re.search(r"title = R\.string\.(\w+)", block)
        paragraphs = re.search(r"paragraphs = listOf\(([^)]*)\)", block)
        if not title or not paragraphs:
            raise ValueError(f"a section of PRIVACY_SECTIONS that can't be read: {block[:80]}")
        found.append((title.group(1), re.findall(r"R\.string\.(\w+)", paragraphs.group(1))))
    if not found:
        raise ValueError("no section in PRIVACY_SECTIONS")
    return found


def version() -> str:
    with open(BUILD, encoding="utf-8") as f:
        build = f.read()
    name = re.search(r'versionName = "([^"]+)"', build)
    code = re.search(r"versionCode = (\d+)", build)
    if not name or not code:
        raise ValueError("no versionName or versionCode in app/build.gradle.kts")
    return f"{name.group(1)} ({code.group(1)})"


def document() -> str:
    text = strings()
    lines = [
        f"# ProxyFlow — {text['privacy_title']}",
        "",
        "The privacy policy the app shows (About & privacy, then Privacy policy), for the public address Google Play asks "
        "for (owner decision D9: where it is published, and the support contact). Written from the app's own text by "
        "`scripts/privacy_policy.py`; CI fails if the two differ. Do not edit by hand.",
        "",
        f"*{text['privacy_applies'].replace('%1$s', version())}*",
        "",
        text["privacy_summary"],
        "",
    ]
    for title, paragraphs in sections():
        lines += [f"## {text[title]}", ""]
        for paragraph in paragraphs:
            lines += [text[paragraph], ""]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    text = document()
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
