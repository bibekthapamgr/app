#!/usr/bin/env python3
"""Checks every library the release build ships against OSV, the open vulnerability database (https://osv.dev), M8.

Reads the release runtime classpath as Gradle prints it (`./gradlew -q :app:dependencies --configuration
releaseRuntimeClasspath`), asks OSV about each resolved Maven library at its resolved version, and fails when one has
a known vulnerability rated HIGH or CRITICAL — or one whose rating can't be told — unless scripts/osv_allowlist.json
names it with the reason it can't reach ProxyFlow and a date by which that is looked at again. The Go engine's own
dependencies are checked by govulncheck in the engine job; this covers the Kotlin and Android side.

A rating is the higher of the advisory's own severity (GitHub's advisories give one) and its CVSS v3 vector's, scored
here by the CVSS 3.1 specification. OSV not answering fails the check: it can't be shown clean. So does a line of the
tree that can't be read, or a library Gradle could not resolve: what is not read is not shown clean either.

Usage: osv_scan.py <gradle dependencies output> [--allowlist FILE]
"""

from __future__ import annotations

import argparse
import datetime
import http.client
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
from typing import Callable

QUERY_BATCH = "https://api.osv.dev/v1/querybatch"
VULN = "https://api.osv.dev/v1/vulns/"
FAILING = {"HIGH", "CRITICAL", "UNKNOWN"}
DEFAULT_ALLOWLIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "osv_allowlist.json")

# A line of Gradle's tree: "+--- …" or "\\--- …", indented with "|" and spaces.
TREE_LINE = re.compile(r"^[| ]*[+\\]--- (?P<entry>.+?)\s*$")
# "group:artifact:1.0", "group:artifact:1.0 -> 1.1 (*)", "group:artifact -> 1.1" (a BOM's version),
# "group:artifact:{strictly 1.0} -> 1.0", "group:artifact:1.0 -> other.group:other:2.0" (a module replaced by another).
ENTRY = re.compile(
    r"^(?P<coordinates>[^\s:]+:[^\s:]+)(?::(?P<version>\{[^}]*\}|[^\s{}]+))?"
    r"(?: -> (?P<resolved>[^\s]+))?(?P<marks>(?: \([^)]*\))*)$",
)
# The version a rich declaration settles on when Gradle prints no "->": {strictly 1.0}, {require 1.0; reject 1.1}…
RICH = re.compile(r"\b(?:strictly|require|prefer) ([^\s;}]+)")


def read_tree(lines: list[str]) -> tuple[dict[str, str], list[str]]:
    """group:artifact -> the version Gradle resolved, from its dependency tree (projects and constraints left out), and
    the lines that could not be read or name a library Gradle could not resolve."""
    found: dict[str, str] = {}
    unread: list[str] = []
    for line in lines:
        tree = TREE_LINE.match(line.rstrip())
        if not tree:
            continue  # a heading, a legend, a blank line: not a dependency
        entry = tree.group("entry")
        if entry.startswith("project ") or " -> project " in entry:
            continue  # one of ProxyFlow's own modules, also when it replaces a library (a dependency substitution)
        if entry.endswith(" FAILED"):
            unread.append(f"Gradle could not resolve {entry[: -len(' FAILED')]}")
            continue
        m = ENTRY.match(entry)
        if not m:
            unread.append(f"a dependency line that can't be read: {entry[:160]}")
            continue
        if "(c)" in m.group("marks") or "(n)" in m.group("marks"):
            continue  # a constraint or an unresolved declaration: not on the classpath as such
        coordinates, resolved, declared = m.group("coordinates"), m.group("resolved"), m.group("version")
        if resolved and resolved.count(":") == 2:
            # Replaced by another module: that one is on the classpath, at its own version.
            group, artifact, version = resolved.split(":")
            found[f"{group}:{artifact}"] = version
            continue
        if resolved and ":" not in resolved:
            found[coordinates] = resolved
            continue
        if resolved:
            unread.append(f"a dependency line that can't be read: {entry[:160]}")
            continue
        if declared and declared.startswith("{"):
            rich = RICH.search(declared)
            if not rich:
                unread.append(f"a version Gradle settled without saying which: {entry[:160]}")
                continue
            declared = rich.group(1)
        if not declared or any(c in declared for c in "+[]()"):
            unread.append(f"a library without a resolved version: {entry[:160]}")
            continue
        found[coordinates] = declared
    return found, unread


def resolved_libraries(lines: list[str]) -> dict[str, str]:
    """group:artifact -> the version Gradle resolved (see read_tree)."""
    return read_tree(lines)[0]


# --- CVSS 3.x base score (https://www.first.org/cvss/v3.1/specification-document, section 7) ------------------------
WEIGHTS = {
    "AV": {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2},
    "AC": {"L": 0.77, "H": 0.44},
    "UI": {"N": 0.85, "R": 0.62},
    "C": {"H": 0.56, "L": 0.22, "N": 0.0},
    "I": {"H": 0.56, "L": 0.22, "N": 0.0},
    "A": {"H": 0.56, "L": 0.22, "N": 0.0},
}
PRIVILEGES = {"U": {"N": 0.85, "L": 0.62, "H": 0.27}, "C": {"N": 0.85, "L": 0.68, "H": 0.5}}


def roundup(value: float) -> float:
    """CVSS 3.1's Roundup: the smallest number with one decimal that is not less than value."""
    whole = round(value * 100_000)
    return whole / 100_000.0 if whole % 10_000 == 0 else (math.floor(whole / 10_000) + 1) / 10.0


def cvss3_score(vector: str) -> float | None:
    """The base score of a CVSS:3.0 or 3.1 vector; None when it is not one."""
    parts = vector.split("/")
    if not parts or not re.fullmatch(r"CVSS:3\.[01]", parts[0]):
        return None
    metrics = dict(part.split(":", 1) for part in parts[1:] if ":" in part)
    try:
        scope = metrics["S"]
        iss = 1 - (1 - WEIGHTS["C"][metrics["C"]]) * (1 - WEIGHTS["I"][metrics["I"]]) * (1 - WEIGHTS["A"][metrics["A"]])
        impact = 6.42 * iss if scope == "U" else 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02) ** 15
        exploitability = 8.22 * WEIGHTS["AV"][metrics["AV"]] * WEIGHTS["AC"][metrics["AC"]] * PRIVILEGES[scope][metrics["PR"]] * WEIGHTS["UI"][metrics["UI"]]
    except KeyError:
        return None
    if impact <= 0:
        return 0.0
    if scope == "U":
        return roundup(min(impact + exploitability, 10))
    return roundup(min(1.08 * (impact + exploitability), 10))


def rating(score: float) -> str:
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    return "LOW" if score > 0 else "NONE"


LEVELS = ["NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"]


def severity_of(advisory: dict) -> tuple[str, str]:
    """The advisory's rating — the higher of its own and its CVSS v3 vector's — and where it came from."""
    rated: list[tuple[str, str]] = []
    own = str((advisory.get("database_specific") or {}).get("severity") or "").upper()
    if own:
        rated.append(("MEDIUM" if own == "MODERATE" else own, "the advisory's own rating"))
    scores = [s for entry in advisory.get("severity") or [] if (s := cvss3_score(str(entry.get("score", "")))) is not None]
    if scores:
        rated.append((rating(max(scores)), f"CVSS v3 {max(scores)}"))
    known = [r for r in rated if r[0] in LEVELS]
    if not known:
        return "UNKNOWN", f"a rating not understood: {own}" if own else "no rating and no CVSS v3 vector"
    return max(known, key=lambda r: LEVELS.index(r[0]))


# --- OSV ------------------------------------------------------------------------------------------------------------
def post_json(url: str, body: dict | None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "proxyflow-ci-osv-scan"})
    last: Exception | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException, json.JSONDecodeError) as e:
            last = e  # a reset or cut connection too: asked again
            time.sleep(2**attempt)
    raise RuntimeError(f"OSV did not answer {url}: {last}")


def query(libraries: dict[str, str], fetch: Callable[[str, dict | None], dict] = post_json) -> dict[str, list[str]]:
    """group:artifact -> the ids of the advisories OSV has for it at its version."""
    names = sorted(libraries)
    found: dict[str, list[str]] = {}
    for start in range(0, len(names), 500):
        chunk = names[start : start + 500]
        body = {"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": libraries[n]} for n in chunk]}
        results = fetch(QUERY_BATCH, body).get("results", [])
        if len(results) != len(chunk):
            raise RuntimeError(f"OSV answered {len(results)} of {len(chunk)} queries")
        for name, result in zip(chunk, results):
            if result.get("next_page_token"):
                raise RuntimeError(f"OSV has more advisories for {name} than one page: not read in full")
            ids = [v["id"] for v in result.get("vulns") or []]
            if ids:
                found[name] = ids
    return found


def allowed(allowlist: dict, advisory_id: str, library: str, today: datetime.date) -> tuple[bool, str]:
    entry = allowlist.get(advisory_id)
    if not entry:
        return False, ""
    if entry.get("package") != library:
        return False, f"allowlisted for {entry.get('package')}, not {library}"
    try:
        until = datetime.date.fromisoformat(str(entry.get("review_by")))
    except ValueError:
        return False, "allowlist entry without a valid review_by date"
    if until < today:
        return False, f"allowlist entry expired on {until}: look at it again"
    if not str(entry.get("reason", "")).strip():
        return False, "allowlist entry without a reason"
    return True, f"allowed until {until}: {entry['reason']}"


def check(
    tree: list[str],
    allowlist: dict,
    fetch: Callable[[str, dict | None], dict] = post_json,
    today: datetime.date | None = None,
) -> tuple[list[str], list[str]]:
    """(facts, problems) for the libraries in Gradle's dependency tree."""
    today = today or datetime.datetime.now(datetime.timezone.utc).date()
    libraries, unread = read_tree(tree)
    facts = [f"{len(libraries)} libraries on the release runtime classpath, each asked of OSV at its resolved version"]
    problems: list[str] = list(unread)
    if not libraries:
        return facts, problems + ["no library found in the dependency tree: the Gradle output was not read"]
    for library, ids in sorted(query(libraries, fetch).items()):
        for advisory_id in ids:
            advisory = fetch(VULN + advisory_id, None)
            level, source = severity_of(advisory)
            summary = str(advisory.get("summary") or advisory.get("details") or "")[:140]
            line = f"{library}:{libraries[library]} {advisory_id} {level} ({source}) {summary}"
            if level in FAILING:
                ok, why = allowed(allowlist, advisory_id, library, today)
                if ok:
                    facts.append(f"{line} — {why}")
                else:
                    problems.append(line + (f" — {why}" if why else ""))
            else:
                facts.append(line)
    if len(facts) == 1:
        facts.append("OSV knows no vulnerability in any of them")
    return facts, problems


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("tree")
    parser.add_argument("--allowlist", default=DEFAULT_ALLOWLIST)
    args = parser.parse_args(argv[1:])
    with open(args.tree, encoding="utf-8") as f:
        tree = f.read().splitlines()
    try:
        with open(args.allowlist, encoding="utf-8") as f:
            allowlist = json.load(f)
    except FileNotFoundError:
        allowlist = {}
    facts, problems = check(tree, allowlist)
    print("\n".join(facts))
    print("\n".join(f"PROBLEM {p}" for p in problems) or "OK: no HIGH or CRITICAL vulnerability in the release build's libraries")
    if problems:
        print(f"::error title=Known vulnerabilities (OSV)::{escape(chr(10).join(problems))}")
        return 1
    print(f"::notice title=Known vulnerabilities (OSV)::{escape(chr(10).join(facts))}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # a scan that can't finish is a failed scan
        print(f"::error title=Known vulnerabilities (OSV)::the scan could not finish: {escape(str(e))}")
        sys.exit(1)
