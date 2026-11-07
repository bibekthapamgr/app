#!/usr/bin/env python3
"""The app's data behaviour, locked (M12, brief task 8: "review privacy policy and Play declarations whenever data
behavior changes"). A fingerprint of everything that decides what ProxyFlow keeps or sends is kept in
docs/m12/data-behavior.lock with the date and version it was last reviewed under; CI fails when any of it changed
without that review, so the privacy policy, the Data safety form and the VpnService declaration cannot drift from the
app by accident.

What is fingerprinted (each a sha256 of the file or the lines named):
  - the permissions and the foreground-service type: every <uses-permission>, <service> and foregroundServiceType in
    the manifests of app/ and core/vpn/;
  - the network destinations ProxyFlow itself opens: PREFLIGHT_TARGET in VpnSession.kt (the connectivity check);
  - what is stored: the Room schemas (core/data/schemas/**), the DataStore keys (the preference names of
    SettingsRepository, ActiveProfileStore and DataStoreProtectionStore), the log's retention constants (LogRepository);
  - what the app says about it: the privacy_* strings and docs/m9/privacy-policy.md;
  - the Play declarations: docs/m11/declarations.md;
  - the libraries on the release runtime classpath, as docs/m4/dependency-review.md lists them (an SDK that phones home
    is a data behaviour).

    python3 scripts/check_data_behavior.py            # compare with the lock; exit 1 on a change, naming it
    python3 scripts/check_data_behavior.py --update --reviewed-by "<name>" --note "<what was reviewed>"
                                                      # after the review of docs/m12/policy-review.md: rewrite the lock
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
LOCK = os.path.join(ROOT, "docs", "m12", "data-behavior.lock")


def read(path: str) -> str:
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def manifest_lines(path: str) -> str:
    text = read(path)
    keep = re.findall(r"<uses-permission[^>]*>|<service\b[^>]*>|foregroundServiceType=\"[^\"]*\"|android:permission=\"[^\"]*\"|<uses-feature[^>]*>", text, re.S)
    return "\n".join(re.sub(r"\s+", " ", k).strip() for k in keep)


def strings_named(path: str, prefix: str) -> str:
    text = read(path)
    found = re.findall(rf"<string name=\"({prefix}[^\"]*)\">(.*?)</string>", text, re.S)
    return "\n".join(f"{name}={re.sub(r'\\s+', ' ', body).strip()}" for name, body in found)


def constants(path: str, names: list[str]) -> str:
    text = read(path)
    out = []
    for name in names:
        m = re.search(rf"(?:const\s+)?val\s+{name}\s*(?::\s*[\w<>?]+)?\s*=\s*([^\n]+)", text)
        out.append(f"{name}={m.group(1).strip() if m else '<not found>'}")
    return "\n".join(out)


def tree(path: str) -> str:
    base = os.path.join(ROOT, path)
    parts = []
    for dirpath, _dirs, files in sorted(os.walk(base)):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            with open(full, "rb") as f:
                parts.append(f"{os.path.relpath(full, ROOT)} {hashlib.sha256(f.read()).hexdigest()}")
    return "\n".join(parts)


def datastore_keys(path: str) -> str:
    text = read(path)
    return "\n".join(sorted(set(re.findall(r"(?:stringPreferencesKey|booleanPreferencesKey|intPreferencesKey|longPreferencesKey|stringSetPreferencesKey)\(\"([^\"]+)\"\)", text))))


def items() -> dict[str, str]:
    """Name → what is hashed. The names are stable; a new item is a reviewed change too."""
    out = {
        "manifest:app": manifest_lines("app/src/main/AndroidManifest.xml"),
        "manifest:core/vpn": manifest_lines("core/vpn/src/main/AndroidManifest.xml"),
        "network:preflight-target": constants("core/vpn/src/main/kotlin/app/proxyflow/core/vpn/VpnSession.kt", ["PREFLIGHT_TARGET"]),
        "stored:room-schemas": tree("core/data/schemas"),
        "stored:datastore-keys": "\n".join(
            datastore_keys(f"core/data/src/main/kotlin/app/proxyflow/core/data/{name}.kt")
            for name in ("SettingsRepository", "ActiveProfileStore", "DataStoreProtectionStore")
        ),
        "stored:log-retention": constants("core/data/src/main/kotlin/app/proxyflow/core/data/LogRepository.kt", ["RETENTION_MILLIS", "MAX_ENTRIES"]),
        "says:privacy-strings": strings_named("feature/about/src/main/res/values/strings.xml", "privacy_"),
        "says:privacy-policy-doc": read("docs/m9/privacy-policy.md"),
        "says:play-declarations": read("docs/m11/declarations.md"),
        "libraries:dependency-review": read("docs/m4/dependency-review.md"),
    }
    return out


def fingerprints() -> dict[str, str]:
    return {name: sha(text) for name, text in items().items()}


def load_lock() -> dict:
    with open(LOCK, encoding="utf-8") as f:
        return json.load(f)


def check() -> int:
    if not os.path.isfile(LOCK):
        print(f"::error title=Data behaviour::no lock at {os.path.relpath(LOCK, ROOT)}; run check_data_behavior.py --update after the review")
        return 1
    lock = load_lock()
    now = fingerprints()
    changed = [name for name in now if lock["items"].get(name) != now[name]]
    gone = [name for name in lock["items"] if name not in now]
    if changed or gone:
        what = ", ".join(changed + [f"{g} (removed)" for g in gone])
        print(f"::error title=Data behaviour changed::{what} — what ProxyFlow keeps, sends or declares changed since the review of {lock['reviewed_on']} ({lock['version']}). Review docs/m9/privacy-policy.md, the Data safety form and the VpnService declaration as docs/m12/policy-review.md says, then run scripts/check_data_behavior.py --update with --reviewed-by and --note")
        for name in changed:
            print(f"PROBLEM changed: {name}")
        for name in gone:
            print(f"PROBLEM removed: {name}")
        return 1
    print(f"OK: the data behaviour is as reviewed on {lock['reviewed_on']} for version {lock['version']} ({len(now)} items)")
    return 0


def version() -> str:
    text = read("app/build.gradle.kts")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return f"{name.group(1) if name else '?'} ({code.group(1) if code else '?'})"


def update(reviewed_by: str, note: str) -> int:
    lock = {
        "reviewed_on": dt.date.today().isoformat(),
        "reviewed_by": reviewed_by,
        "version": version(),
        "note": note,
        "items": fingerprints(),
    }
    os.makedirs(os.path.dirname(LOCK), exist_ok=True)
    with open(LOCK, "w", encoding="utf-8") as f:
        json.dump(lock, f, indent=2)
        f.write("\n")
    print(f"locked {len(lock['items'])} items for {lock['version']}, reviewed by {reviewed_by} on {lock['reviewed_on']}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--reviewed-by", default="")
    parser.add_argument("--note", default="")
    args = parser.parse_args(argv)
    if args.update:
        if not args.reviewed_by or not args.note:
            print("--update needs --reviewed-by and --note (who reviewed the policy and declarations, and what changed)", file=sys.stderr)
            return 2
        return update(args.reviewed_by, args.note)
    return check()


if __name__ == "__main__":
    sys.exit(main())
