#!/usr/bin/env python3
"""Finds what Android's resource compiler refuses, or reads otherwise than written, in the app's string resources: an
apostrophe or a double quote without its backslash, and a backslash before anything but ' " \\ n t @ ? or uXXXX.

One such string fails the whole build at resource merging, every device job's too, minutes in and with a message that
names no string ("Can not extract resource from ParsedResource": M10-6). This finds it in a second, with the file, the
line and the string's name. A string wholly in double quotes may hold an apostrophe as it is (Android's own rule), but
not one with style spans in it (<b>, <i>, <a>): the resource compiler starts its quoting afresh at each span, so this
script asks for the backslash in the pieces quoting no longer covers. An <xliff:g>, a part kept from translation, is no
span: quoting goes on across it.

Usage: check_strings.py [ROOT]  (default: the repository; every src/<set>/res/values*/*.xml under it, build output left out)
Exit status: 0 when nothing is found, 1 otherwise.
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ESCAPABLE = set("'\"\\nt@?#")
XLIFF_G = "{urn:oasis:names:tc:xliff:document:1.2}g"
HEX4 = re.compile(r"[0-9a-fA-F]{4}")


def scan(body: str, quoted: bool, edges: tuple[int, int] = (-1, -1)) -> list[str]:
    """What is wrong in a piece of a string's text, quoting on at its start if [quoted]: a double quote at an index in
    [edges] is the string's own opening or closing quote, which quotes rather than shows."""
    found: list[str] = []
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
            if i not in edges:
                found.append('a double quote without its backslash (write \\")')
            quoted = not quoted
        i += 1
    return found


def problems_in(text: str) -> list[str]:
    """What is wrong in one string's text, with no style span in it, as Android reads it (XML entities decoded)."""
    body = text.strip()
    if len(body) >= 2 and body.startswith('"') and body.endswith('"'):
        return scan(body[1:-1], quoted=True)
    return scan(body, quoted=False)


def problems_in_segments(segments: list[str]) -> list[str]:
    """What is wrong in a string's text split at its style spans: the resource compiler starts its quoting afresh, off,
    at each span's start and end, so only the piece before the first span is quoted by the string's opening quote; its
    closing quote, at the end of the last piece, quotes nothing after it."""
    if len(segments) == 1:
        return problems_in(segments[0])
    found: list[str] = []
    last = len(segments) - 1
    for index, segment in enumerate(segments):
        lead = len(segment) - len(segment.lstrip()) if index == 0 else -1
        trail = len(segment.rstrip()) - 1 if index == last else -1
        opening = index == 0 and segment[lead : lead + 1] == '"'
        closing = index == last and trail >= 0 and segment[trail] == '"'
        found += scan(segment, quoted=False, edges=(lead if opening else -1, trail if closing else -1))
    return found


def segments_of(element: ET.Element) -> list[str]:
    """A string's text, split where a style span starts or ends; an <xliff:g> splits nothing, a span inside one does."""
    segments = [element.text or ""]

    def walk(node: ET.Element) -> None:
        for child in node:
            if child.tag == XLIFF_G:
                segments[-1] += child.text or ""
                walk(child)
            else:
                segments.append(child.text or "")
                walk(child)
                segments.append("")
            segments[-1] += child.tail or ""

    walk(element)
    return segments


def strings_of(path: pathlib.Path) -> list[tuple[str, list[str]]]:
    """(name, text split at its style spans) of every string, plural item and array item in a values file."""
    root = ET.parse(path).getroot()
    out: list[tuple[str, list[str]]] = []
    for element in root:
        tag = element.tag
        name = element.get("name", "?")
        if tag == "string":
            out.append((name, segments_of(element)))
        elif tag in ("plurals", "string-array", "array"):
            for index, item in enumerate(element.iter("item")):
                out.append((f"{name}[{item.get('quantity', index)}]", segments_of(item)))
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
        for name, segments in entries:
            for problem in problems_in_segments(segments):
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
