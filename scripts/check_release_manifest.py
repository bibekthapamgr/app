#!/usr/bin/env python3
"""Checks the merged manifest of the release build: the one users install.

ManifestTest runs on the debug build under Robolectric. This script reads the
release build's manifest after every library's manifest was merged into it, and
fails CI unless:

- it requests only the permissions in ALLOWED_PERMISSIONS (a milestone that needs
  a new one, such as INTERNET in M5, adds it there with the reason);
- backups are off: allowBackup="false", and both dataExtractionRules (Android 12+)
  and fullBackupContent (Android 10 and 11) point at rule files in
  app/src/main/res/xml that exclude every storage domain the app writes to;
- it is not debuggable and does not opt in to cleartext traffic;
- the only exported component without a permission is the launcher activity.

Usage: check_release_manifest.py [merged AndroidManifest.xml]
(default: the release build's merged manifest under app/build/intermediates)
"""

from __future__ import annotations

import glob
import secrets
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
RES_XML = "app/src/main/res/xml"

# Each entry: permission (with ${applicationId} for the app's own) -> why the app needs it.
ALLOWED_PERMISSIONS = {
    "${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": (
        "declared and used by androidx.core to keep the app's own runtime-registered receivers private; grants nothing"
    ),
}

# Domains the app can write to without direct-boot support (device-protected storage is not used).
BACKUP_DOMAINS = {"root", "file", "database", "sharedpref", "external"}
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def excluded_domains(section: ET.Element) -> set[str]:
    return {e.get("domain", "") for e in section.findall("exclude") if e.get("path") in (".", "./", "")} - {""}


def check_backup_rules(application: ET.Element) -> list[str]:
    problems = []
    if attr(application, "allowBackup") != "false":
        problems.append('android:allowBackup must be "false"')

    rules = {"dataExtractionRules": ("cloud-backup", "device-transfer"), "fullBackupContent": (None,)}
    for attribute, sections in rules.items():
        reference = attr(application, attribute)
        if not reference or not reference.startswith("@xml/"):
            problems.append(f"android:{attribute} must point at an @xml rule file, found {reference!r}")
            continue
        path = f"{RES_XML}/{reference.removeprefix('@xml/')}.xml"
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as error:
            problems.append(f"android:{attribute}: cannot read {path}: {error}")
            continue
        for section_name in sections:
            section = root if section_name is None else root.find(section_name)
            where = path if section_name is None else f"{path} <{section_name}>"
            if section is None:
                problems.append(f"{where} is missing")
                continue
            if section.findall("include"):
                problems.append(f"{where} must not include anything")
            missing = BACKUP_DOMAINS - excluded_domains(section)
            if missing:
                problems.append(f"{where} does not exclude the domains {sorted(missing)}")
    return problems


def is_launcher(component: ET.Element) -> bool:
    for intent_filter in component.findall("intent-filter"):
        actions = {attr(a, "name") for a in intent_filter.findall("action")}
        categories = {attr(c, "name") for c in intent_filter.findall("category")}
        if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
            return True
    return False


def check(manifest_path: str) -> tuple[list[str], list[str]]:
    root = ET.parse(manifest_path).getroot()
    package = root.get("package") or ""
    allowed = {name.replace("${applicationId}", package) for name in ALLOWED_PERMISSIONS}
    problems: list[str] = []
    facts = [f"package {package}"]

    requested = [attr(p, "name") or "" for p in root.findall("uses-permission") + root.findall("uses-permission-sdk-23")]
    declared = [attr(p, "name") or "" for p in root.findall("permission")]
    facts.append(f"uses-permission {requested or 'none'}")
    facts.append(f"declares permission {declared or 'none'}")
    for name in requested + declared:
        if name not in allowed:
            problems.append(f"permission {name} is not in ALLOWED_PERMISSIONS (scripts/check_release_manifest.py)")

    application = root.find("application")
    if application is None:
        return problems + ["no <application> element"], facts
    problems += check_backup_rules(application)
    if attr(application, "debuggable") == "true":
        problems.append('android:debuggable="true" in a release build')
    if attr(application, "usesCleartextTraffic") == "true":
        problems.append('android:usesCleartextTraffic="true": proxy connections must not opt in to cleartext')

    for kind in COMPONENTS:
        for component in application.findall(kind):
            name = attr(component, "name") or "?"
            exported = attr(component, "exported")
            if exported is None and component.findall("intent-filter"):
                problems.append(f"{kind} {name} has an intent filter but no explicit android:exported")
            if exported != "true":
                continue
            permission = attr(component, "permission")
            facts.append(f"exported {kind} {name}" + (f" (needs {permission})" if permission else ""))
            if not permission and not (kind == "activity" and is_launcher(component)):
                problems.append(f"{kind} {name} is exported without a permission")
    return problems, facts


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        path = argv[1]
    else:
        found = sorted(glob.glob("app/build/intermediates/merged_manifest/release/*/AndroidManifest.xml"))
        if len(found) != 1:
            message = f"expected one merged release manifest, found {found}; run assembleRelease first"
            print(f"::error title=release manifest::{escape(message)}")
            return 2
        path = found[0]
    problems, facts = check(path)
    # The manifest comes from the code under test: print it with workflow commands off.
    token = secrets.token_hex(16)
    print(f"::stop-commands::{token}")
    print(f"Release manifest {path}")
    print("\n".join(f"  {fact}" for fact in facts))
    print("\n".join(f"PROBLEM {problem}" for problem in problems) or "OK: permissions, backups, debuggable, cleartext, exports")
    print(f"::{token}::")
    if problems:
        print(f"::error title=release manifest::{escape(chr(10).join(problems))}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
