#!/usr/bin/env python3
"""Google Play's target API requirement against the app's targetSdk (M12, brief task 4: "update dependencies and target
API as required"). The rules are in docs/m12/play-target-api.json, read from Google's page by the owner with the date
they were read; this script:

  - fails when a requirement whose date has passed asks for a higher API than gradle/libs.versions.toml's targetSdk;
  - warns when one is due within --lead days (default 120: the time a targetSdk bump, its compatibility run on the new
    emulator image and a release take), including the expected next requirement if the page has not named it yet;
  - warns when the rules were read more than --stale days ago (default 180): the owner re-reads the page.

    python3 scripts/check_target_api.py [--today YYYY-MM-DD] [--lead 120] [--stale 180]

Run monthly by .github/workflows/maintenance.yml, which opens an issue on a warning or a failure.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def target_sdk() -> int:
    with open(os.path.join(ROOT, "gradle", "libs.versions.toml"), encoding="utf-8") as f:
        m = re.search(r'^targetSdk\s*=\s*"(\d+)"', f.read(), re.M)
    if not m:
        raise SystemExit("gradle/libs.versions.toml: no targetSdk")
    return int(m.group(1))


def rules() -> dict:
    with open(os.path.join(ROOT, "docs", "m12", "play-target-api.json"), encoding="utf-8") as f:
        return json.load(f)


def check(today: dt.date, lead: int, stale: int, extended: bool = False) -> tuple[list[str], list[str]]:
    """(failures, warnings). With extended=True a requirement past its date is only a warning until its
    extension_until (the owner asked Google for the extension and got it: docs/m12/maintenance-runbook.md §3.3)."""
    failures: list[str] = []
    warnings: list[str] = []
    data = rules()
    target = target_sdk()
    read_on = dt.date.fromisoformat(data["read_on"])
    if (today - read_on).days > stale:
        warnings.append(f"the Play target API rules were read on {read_on}, {(today - read_on).days} days ago: re-read {data['source']} and update docs/m12/play-target-api.json")
    for rule in sorted(data["requirements"], key=lambda r: r["api"], reverse=True):
        due = dt.date.fromisoformat(rule["new_apps_and_updates_from"])
        if rule["api"] > target:
            extension = dt.date.fromisoformat(rule["extension_until"]) if rule.get("extension_until") else None
            granted = extended or bool(rule.get("extension_granted"))
            if due <= today and granted and extension and today <= extension:
                warnings.append(f"Google Play has required targetSdk {rule['api']} (Android {rule['android']}) since {due}; the app targets {target} under an extension that ends {extension}, in {(extension - today).days} days")
            elif due <= today:
                failures.append(f"Google Play has required targetSdk {rule['api']} (Android {rule['android']}) for new apps and updates since {due}; the app targets {target}" + (f" (an extension, if Google granted one, would end {extension}: run with --extended)" if extension and today <= extension else ""))
            elif (due - today).days <= lead:
                warnings.append(f"Google Play requires targetSdk {rule['api']} (Android {rule['android']}) from {due}, in {(due - today).days} days; the app targets {target}")
    nxt = data.get("expected_next")
    known = {r["api"] for r in data["requirements"]}
    if nxt and nxt["api"] <= max(known):
        warnings.append(f"docs/m12/play-target-api.json: expected_next is API {nxt['api']}, which the requirements already list; set it to {max(known) + 1} with Google's pattern date, so the next year's lead time is kept")
    elif nxt and nxt["api"] > target:
        due = dt.date.fromisoformat(nxt["new_apps_and_updates_from"])
        if (due - today).days <= lead:
            when = f"in {(due - today).days} days" if due >= today else f"{(today - due).days} days ago"
            warnings.append(f"by Google's pattern, targetSdk {nxt['api']} (Android {nxt['android']}) is expected to be required from {due}, {when}; the app targets {target} — confirm on {data['source']} and update docs/m12/play-target-api.json")
    return failures, warnings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--today", default=dt.date.today().isoformat())
    parser.add_argument("--lead", type=int, default=120)
    parser.add_argument("--stale", type=int, default=180)
    parser.add_argument("--extended", action="store_true", help="as if every rule had extension_granted (a local dry run); in CI the owner sets extension_granted in docs/m12/play-target-api.json when Play Console confirms the extension")
    args = parser.parse_args(argv)
    failures, warnings = check(dt.date.fromisoformat(args.today), args.lead, args.stale, args.extended)
    for w in warnings:
        print(f"::warning title=Target API::{w}")
    for f in failures:
        print(f"::error title=Target API::{f}")
    if not failures and not warnings:
        print(f"OK: targetSdk {target_sdk()} meets Google Play's requirement (rules read {rules()['read_on']}); nothing due within {args.lead} days")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
