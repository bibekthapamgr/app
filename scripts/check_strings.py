#!/usr/bin/env python3
"""Finds what Android's resource compiler refuses, or reads otherwise than written, in the app's string resources: an
apostrophe or a double quote without its backslash, and a backslash before anything but ' " \\ n t @ ? or uXXXX.

One such string fails the whole build at resource merging, every device job's too, minutes in and with a message that
names no string ("Can not extract resource from ParsedResource": M10-6). This finds it in a second, with the file, the
line and the string's name. A string wholly in double quotes may hold an apostrophe as it is (Android's own rule), but
not one with markup in it (<b>, <xliff:g>): the resource compiler starts its quoting afresh at each span, so this script
asks for the backslash there.

Usage: check_strings.py [ROOT]  (default: the repository; every src/<set>/res/values*/*.xml under it, build output left out)
Exit status: 0 when nothing is found, 1 otherwise.
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ESCAPABLE = set("'\"\\nt@?#")
HEX4 = re.compile(r"[0-9a-fA-F]{4}")


def problems_in(text: str, markup: bool = False) -> list[str]:
    """What is wrong in one string's text as Android reads it (XML entities already decoded); [markup]: it has spans."""
    found: list[str] = []
    body = text.strip()
    quoted = len(body) >= 2 and body.startswith('"') and body.endswith('"') and not markup
    if quoted:
        body = body[1:-1]
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\":
            following = body[i + 1 : i + 2]
            if following == "u":
                if not HEX4.fullmatch(body[i + 2 : i + 6]):
                    found.append("a \\u escape without four hex digits after it")
                i += 6
                continue
            if following not in ESCAPABLE or following == "":
                found.append(f"a backslash before {following!r}, which Android does not escape" if following else "a backslash at the end")
            i += 2
            continue
        if c == "'" and not quoted:
            found.append("an apostrophe without its backslash (write \\')")
        elif c == '"':
            found.append('a double quote without its backslash (write \\")')
        i += 1
    return found


def strings_of(path: pathlib.Path) -> list[tuple[str, str, bool]]:
    """(name, text, whether it has markup) of every string, plural item and array item in a values file."""
    root = ET.parse(path).getroot()
    out: list[tuple[str, str, bool]] = []
    for element in root:
        tag = element.tag
        name = element.get("name", "?")
        if tag == "string":
            out.append((name, "".join(element.itertext()), len(element) > 0))
        elif tag in ("plurals", "string-array", "array"):
            for index, item in enumerate(element.iter("item")):
                label = f"{name}[{item.get('quantity', index)}]"
                out.append((label, "".join(item.itertext()), len(item) > 0))
    return out


def line_of(source: str, name: str) -> int:
    plain = name.split("[", 1)[0]
    match = re.search(r'name="' + re.escape(plain) + r'"', source)
    return source.count("\n", 0, match.start()) + 1 if match else 0


def check(root: pathlib.Path) -> list[str]:
    report: list[str] = []
    for path in sorted(root.glob("**/src/*/res/values*/*.xml")):
        if "build" in path.relative_to(root).parts:
            continue
        source = path.read_text(encoding="utf-8")
        try:
            entries = strings_of(path)
        except ET.ParseError as e:
            report.append(f"{path.relative_to(root)}: not well-formed XML: {e}")
            continue
        for name, text, markup in entries:
            for problem in problems_in(text, markup):
                report.append(f"{path.relative_to(root)}:{line_of(source, name)}: {name}: {problem}")
    return report


def main(argv: list[str]) -> int:
    root = pathlib.Path(argv[1] if len(argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)
    report = check(root)
    for line in report:
        print(line)
    files = len([p for p in root.glob("**/src/*/res/values*/*.xml") if "build" not in p.relative_to(root).parts])
    print(f"{files} values files checked: " + ("nothing found" if not report else f"{len(report)} problems"))
    return 1 if report else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
