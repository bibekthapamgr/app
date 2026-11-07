#!/usr/bin/env python3
"""Whether this tree may be released (M12, acceptance "each release includes changelog and regression-test results"):

  - app/build.gradle.kts's versionName and versionCode are read; the code must be above the last one in
    docs/m12/release-history.md (Google Play refuses a code already uploaded);
  - CHANGELOG.md has a section for exactly that version, "## [<name> (<code>)] — <date>", not "Unreleased" and not
    "not yet on Google Play" when --production is asked, with at least one entry under it;
  - docs/m12/release-history.md has a row for the version with the verified commit, the `android` and `release` run
    ids and the crash-free rate column (a placeholder is allowed until the release is out: the row says "pending");
  - the data-behaviour lock matches the tree (scripts/check_data_behavior.py), so the policy was reviewed for what
    this release keeps and sends;
  - docs/m9/privacy-policy.md describes this version (scripts/privacy_policy.py keeps it so);
  - the Play "What's new" (the listing's listing-notes block) is under 500 characters (check_listing_claims.py does
    the rest).

    python3 scripts/release/check_release_ready.py [--production]

Run in CI on every push (without --production: the changelog section may still say the release is not out), and by
the owner with --production before the hand-started release run.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def read(path: str) -> str:
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def version() -> tuple[str, int]:
    text = read("app/build.gradle.kts")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        raise SystemExit("app/build.gradle.kts: no versionName/versionCode")
    return name.group(1), int(code.group(1))


def changelog_section(name: str, code: int) -> tuple[str, str] | None:
    """(heading remainder, body) of the version's section, or None."""
    text = read("CHANGELOG.md")
    m = re.search(rf"^## \[{re.escape(name)} \({code}\)\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S)
    if not m:
        return None
    heading = re.search(rf"^## \[{re.escape(name)} \({code}\)\]([^\n]*)", text, re.M).group(1)
    return heading, m.group(1)


def history_rows() -> list[dict[str, str]]:
    text = read("docs/m12/release-history.md")
    rows = []
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 6 and re.match(r"^\d+\.\d+\.\d+ \(\d+\)$", cells[0]):
            rows.append({"version": cells[0], "commit": cells[1], "android": cells[2], "release": cells[3], "date": cells[4], "crash_free": cells[5]})
    return rows


def check(production: bool) -> list[str]:
    problems: list[str] = []
    name, code = version()
    label = f"{name} ({code})"

    section = changelog_section(name, code)
    if section is None:
        problems.append(f"CHANGELOG.md has no section '## [{label}]'")
    else:
        heading, body = section
        if "unreleased" in heading.lower():
            problems.append(f"CHANGELOG.md: the section of {label} is marked Unreleased")
        if production and "not yet" in heading.lower():
            problems.append(f"CHANGELOG.md: the section of {label} says the release is not yet out; put the date (— YYYY-MM-DD)")
        if production and not re.search(r"\d{4}-\d{2}-\d{2}", heading):
            problems.append(f"CHANGELOG.md: the section of {label} has no release date")
        if not re.search(r"^- ", body, re.M):
            problems.append(f"CHANGELOG.md: the section of {label} has no entry")
    unreleased = re.search(r"^## \[Unreleased\]\n(.*?)(?=^## \[|\Z)", read("CHANGELOG.md"), re.M | re.S)
    if production and unreleased and re.search(r"^- ", unreleased.group(1), re.M):
        problems.append("CHANGELOG.md: [Unreleased] still has entries; move them to the release's section")

    rows = history_rows()
    mine = [r for r in rows if r["version"] == label]
    others = [r for r in rows if r["version"] != label]
    codes = [int(re.search(r"\((\d+)\)", r["version"]).group(1)) for r in others]
    if codes and code <= max(codes):
        problems.append(f"versionCode {code} is not above the last released code {max(codes)} in docs/m12/release-history.md")
    if not mine:
        problems.append(f"docs/m12/release-history.md has no row for {label}")
    else:
        row = mine[0]
        for field in ("commit", "android", "release"):
            if not re.search(r"[0-9a-f]{7,}|\d{8,}", row[field]) and "pending" not in row[field].lower():
                problems.append(f"docs/m12/release-history.md: {label}'s {field} column is '{row[field]}', not a commit or run id (or 'pending')")
        if production and any("pending" in row[f].lower() for f in ("commit", "android", "release")):
            problems.append(f"docs/m12/release-history.md: {label}'s row still says pending; put the verified commit and the green runs' ids")

    policy = read("docs/m9/privacy-policy.md")
    if f"version {label}" not in policy:
        problems.append(f"docs/m9/privacy-policy.md does not describe version {label}: run scripts/privacy_policy.py --out docs/m9/privacy-policy.md")

    result = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "check_data_behavior.py")], capture_output=True, text=True)
    if result.returncode != 0:
        problems.append("the data behaviour changed since the last policy review (scripts/check_data_behavior.py)")

    listing = read("docs/m11/store-listing.md")
    notes = re.search(r"```listing-notes\n(.*?)```", listing, re.S)
    if not notes:
        problems.append("docs/m11/store-listing.md has no listing-notes block (Play's What's new)")
    elif len(notes.group(1).strip()) > 500:
        problems.append(f"the What's new text is {len(notes.group(1).strip())} characters, over Play's 500")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--production", action="store_true", help="the checks a release to Play must pass (dates, no pending)")
    args = parser.parse_args(argv)
    problems = check(args.production)
    for p in problems:
        print(f"PROBLEM {p}")
    if problems:
        print(f"::error title=Release readiness::{'; '.join(problems)}")
        return 1
    name, code = version()
    print(f"OK: {name} ({code}) has its changelog section, its release-history row, the policy describes it, the data behaviour is as reviewed{' — ready for Play' if args.production else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
