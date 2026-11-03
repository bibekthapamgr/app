#!/usr/bin/env python3
"""Checks the merged manifest of the release build: the one users install.

ManifestTest runs on the debug build under Robolectric. This script reads the
release build's manifest after every library's manifest was merged into it, and
fails CI unless:

- it requests only the permissions in ALLOWED_PERMISSIONS (a milestone that needs
  a new one adds it there with the reason; M5 added the VPN service's);
- the VPN service is not exported, requires BIND_VPN_SERVICE, is of foreground
  type systemExempted and filters for android.net.VpnService only;
- backups are off: allowBackup="false", and both dataExtractionRules (Android 12+)
  and fullBackupContent (Android 10 and 11) point at rule files in
  app/src/main/res/xml that exclude every storage domain the app writes to;
- it is not debuggable and does not opt in to cleartext traffic;
- its network security config (M8) permits no cleartext and trusts the system's certificate authorities only, with
  no per-domain exceptions, no user or bundled certificates and no pin overrides — in app/src/main/res/xml and in
  every qualified variant of it (xml-v31…) and the release build type's own resources, any of which Android may use;
- the only exported component without a permission is the launcher activity;
- what the release APK itself ships (M8) for each @xml file the manifest points at — the network security config and
  both backup rule files — passes the same checks, in every configuration it ships (`aapt2 dump resources`, then
  `aapt2 dump xmltree` of each file): a library's resource of the same name and qualifiers loses to the app's, but a
  qualified variant the app lacks (xml-v31/…) or the release build type's own copy ships too, and Android takes it
  where it applies.

Usage: check_release_manifest.py [merged AndroidManifest.xml [release APK]]
(defaults: the release build's merged manifest under app/build/intermediates and its APK under app/build/outputs;
aapt2 from the newest build-tools under $ANDROID_HOME or $ANDROID_SDK_ROOT)
"""

from __future__ import annotations

import glob
import os
import re
import secrets
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
RES_XML = "app/src/main/res/xml"
# Other resources the release build takes files from besides main's: the release build type's own.
RELEASE_RES = ["app/src/release/res"]

# Each entry: permission (with ${applicationId} for the app's own) -> why the app needs it.
ALLOWED_PERMISSIONS = {
    "${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": (
        "declared and used by androidx.core to keep the app's own runtime-registered receivers private; grants nothing"
    ),
    # M5 (ADR-0010): the VPN service and the engine's connections to the user's proxy.
    "android.permission.INTERNET": "the engine's and the pre-flight check's connections to the user's proxy",
    "android.permission.ACCESS_NETWORK_STATE": (
        "Android's view of the VPN network: Connected waits until Android routes apps into the new interface"
    ),
    "android.permission.FOREGROUND_SERVICE": "the VPN runs as a foreground service with its status notification",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED": (
        "the VPN service's foreground type on Android 14+, allowed for VPN apps the user has consented to (ADR-0007)"
    ),
    "android.permission.POST_NOTIFICATIONS": "the connection status and problem notifications on Android 13+; optional",
}

# The only service, and how it must be declared: Android alone may bind it.
VPN_SERVICE = "app.proxyflow.core.vpn.ProxyFlowVpnService"

# Domains the app can write to without direct-boot support (device-protected storage is not used).
BACKUP_DOMAINS = {"root", "file", "database", "sharedpref", "external"}
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def excluded_domains(section: ET.Element) -> set[str]:
    return {e.get("domain", "") for e in section.findall("exclude") if e.get("path") in (".", "./", "")} - {""}


# Each backup rule attribute of <application>, and the sections of its file that must exclude everything.
BACKUP_RULES = {"dataExtractionRules": ("cloud-backup", "device-transfer"), "fullBackupContent": (None,)}


def check_rules_root(root: ET.Element, where: str, sections: tuple[str | None, ...]) -> list[str]:
    problems = []
    for section_name in sections:
        section = root if section_name is None else root.find(section_name)
        place = where if section_name is None else f"{where} <{section_name}>"
        if section is None:
            problems.append(f"{place} is missing")
            continue
        if section.findall("include"):
            problems.append(f"{place} must not include anything")
        missing = BACKUP_DOMAINS - excluded_domains(section)
        if missing:
            problems.append(f"{place} does not exclude the domains {sorted(missing)}")
    return problems


def check_backup_rules(application: ET.Element) -> list[str]:
    problems = []
    if attr(application, "allowBackup") != "false":
        problems.append('android:allowBackup must be "false"')

    for attribute, sections in BACKUP_RULES.items():
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
        problems += check_rules_root(root, path, sections)
    return problems


def config_files(name: str) -> list[str]:
    """Every file the release build may take @xml/<name> from: main's res/xml and its qualified variants (xml-v31…, which
    Android prefers where they apply), and the same in the release build type's own resources."""
    files = []
    for root in [os.path.dirname(RES_XML)] + RELEASE_RES:
        files += sorted(glob.glob(os.path.join(root, "xml*", f"{name}.xml")))
    return files


def check_network_security_config(application: ET.Element, facts: list[str]) -> list[str]:
    """M8: the secure defaults written out, so neither a library nor a later change loosens them unnoticed."""
    reference = attr(application, "networkSecurityConfig")
    if not reference or not reference.startswith("@xml/"):
        return [f"android:networkSecurityConfig must point at an @xml file, found {reference!r}"]
    name = reference.removeprefix("@xml/")
    main = f"{RES_XML}/{name}.xml"
    paths = config_files(name)
    problems = [] if os.path.normpath(main) in map(os.path.normpath, paths) else [f"android:networkSecurityConfig: cannot read {main}: no such file"]
    for path in paths:
        problems += check_network_security_file(path)
    if not problems:
        facts.append(f"network security config {', '.join(paths)}: no cleartext, system certificate authorities only")
    return problems


def check_network_security_file(path: str) -> list[str]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        return [f"android:networkSecurityConfig: cannot read {path}: {error}"]
    return check_network_security_root(root, path)


def check_network_security_root(root: ET.Element, path: str) -> list[str]:
    problems = []
    if root.tag != "network-security-config":
        problems.append(f"{path}: the root element is <{root.tag}>, not <network-security-config>")
    for element in root:
        if element.tag != "base-config":
            problems.append(f"{path}: <{element.tag}> is not allowed: one <base-config> for every connection")
    bases = root.findall("base-config")
    if len(bases) != 1:
        return problems + [f"{path}: expected one <base-config>, found {len(bases)}"]
    base = bases[0]
    if base.get("cleartextTrafficPermitted") != "false":
        problems.append(f'{path}: <base-config> must say cleartextTrafficPermitted="false"')
    anchors = [(c.get("src"), c.get("overridePins")) for t in base.findall("trust-anchors") for c in t]
    if anchors != [("system", None)]:
        problems.append(f"{path}: the trust anchors must be the system's certificates alone, found {anchors}")
    if [c.tag for c in base if c.tag != "trust-anchors"]:
        problems.append(f"{path}: <base-config> may hold <trust-anchors> only, found {[c.tag for c in base]}")
    return problems


def is_launcher(component: ET.Element) -> bool:
    for intent_filter in component.findall("intent-filter"):
        actions = {attr(a, "name") for a in intent_filter.findall("action")}
        categories = {attr(c, "name") for c in intent_filter.findall("category")}
        if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
            return True
    return False


def check_vpn_service(application: ET.Element, facts: list[str]) -> list[str]:
    services = [s for s in application.findall("service") if attr(s, "name") == VPN_SERVICE]
    if len(services) != 1:
        return [f"expected one {VPN_SERVICE}, found {len(services)}"]
    service = services[0]
    problems = []
    if attr(service, "exported") != "false":
        problems.append(f"{VPN_SERVICE} must be android:exported=\"false\"")
    if attr(service, "permission") != "android.permission.BIND_VPN_SERVICE":
        problems.append(f"{VPN_SERVICE} must require android.permission.BIND_VPN_SERVICE")
    if attr(service, "foregroundServiceType") != "systemExempted":
        problems.append(f"{VPN_SERVICE} must have android:foregroundServiceType=\"systemExempted\"")
    actions = {attr(a, "name") for f in service.findall("intent-filter") for a in f.findall("action")}
    if actions != {"android.net.VpnService"}:
        problems.append(f"{VPN_SERVICE} must filter for android.net.VpnService only, found {sorted(a or '' for a in actions)}")
    facts.append(f"service {VPN_SERVICE} exported=false permission=BIND_VPN_SERVICE type=systemExempted")
    return problems


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

    problems += check_network_security_config(application, facts)
    problems += check_vpn_service(application, facts)

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


# `aapt2 dump resources`: a resource's line, then one line per configuration with its file.
AAPT2_RESOURCE = re.compile(r"^\s*resource 0x[0-9a-fA-F]+ (\w+)/([\w.]+)")
AAPT2_FILE = re.compile(r"^\s*\(([^)]*)\) \(file\) (\S+)")
# `aapt2 dump xmltree`: "E: <name> (line=N)" for an element, its children indented below its "A: <name>=<value>"
# attribute lines; a namespace's "N:" line and text's "T:" line need nothing here.
AAPT2_ELEMENT = re.compile(r"^(\s*)E: (?:\S*:)?([^\s:]+) \(line=\d+\)\s*$")
AAPT2_ATTRIBUTE = re.compile(r"^\s*A: (.*)$")


def xml_references(manifest_path: str) -> list[tuple[str, str]]:
    """The manifest's @xml files the checks here read: (its <application> attribute, the resource's name)."""
    application = ET.parse(manifest_path).getroot().find("application")
    references = []
    for attribute in ("networkSecurityConfig", *BACKUP_RULES):
        reference = attr(application, attribute) if application is not None else None
        if reference and reference.startswith("@xml/"):
            references.append((attribute, reference.removeprefix("@xml/")))
    return references


def packaged_files(dump: str) -> dict[str, list[tuple[str, str]]]:
    """Each resource's files in an APK, from `aapt2 dump resources`: "type/name" -> [(configuration, path)], the
    default configuration as "". aapt2 prints "(<configuration>) (file) <path> type=XML" under each resource, then
    "Flag disabled values:" and the values of build flags that are off, which never apply."""
    files: dict[str, list[tuple[str, str]]] = {}
    current: list[tuple[str, str]] | None = None
    for line in dump.splitlines():
        if match := AAPT2_RESOURCE.match(line):
            current = files.setdefault(f"{match.group(1)}/{match.group(2)}", [])
        elif line.strip() == "Flag disabled values:":
            current = None
        elif current is not None and (match := AAPT2_FILE.match(line)):
            configuration = match.group(1).strip()
            current.append(("" if configuration.lower() == "default" else configuration, match.group(2)))
    return files


def xml_attribute(text: str) -> tuple[str, str]:
    """An attribute as `aapt2 dump xmltree` prints it — `name="value" (Raw: "value")`, a compiled `name=false` or
    `name=@raw/file`, a namespace and resource id before the name — as (name, value), the raw value where printed."""
    head, _, value = text.partition("=")
    name = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", head).rsplit(":", 1)[-1]
    raw = re.search(r' \(Raw: "(.*)"\)$', value)
    if raw:
        return name, raw.group(1)
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        return name, value[1:-1]
    return name, value


def xmltree_root(dump: str) -> ET.Element | None:
    """The element tree `aapt2 dump xmltree` printed for one file, attributes and all; None if it printed none, or
    more than one top element (it never does for a file that parsed)."""
    root: ET.Element | None = None
    open_elements: list[tuple[int, ET.Element]] = []
    for line in dump.splitlines():
        if match := AAPT2_ELEMENT.match(line):
            indent = len(match.group(1))
            while open_elements and open_elements[-1][0] >= indent:
                open_elements.pop()
            if open_elements:
                element = ET.SubElement(open_elements[-1][1], match.group(2))
            elif root is None:
                element = root = ET.Element(match.group(2))
            else:
                return None
            open_elements.append((indent, element))
        elif open_elements and (match := AAPT2_ATTRIBUTE.match(line)):
            name, value = xml_attribute(match.group(1))
            open_elements[-1][1].set(name, value)
    return root


def check_packaged_file(attribute: str, root: ET.Element, where: str) -> list[str]:
    """The checks the source files get, on what the APK ships."""
    if attribute == "networkSecurityConfig":
        return check_network_security_root(root, where)
    return check_rules_root(root, where, BACKUP_RULES[attribute])


def find_aapt2() -> str | None:
    """aapt2 of the newest build-tools in the Android SDK."""
    candidates = []
    for home in filter(None, (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"))):
        candidates += glob.glob(os.path.join(home, "build-tools", "*", "aapt2"))

    def version(path: str) -> list[int]:
        return [int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", os.path.basename(os.path.dirname(path)))]

    return max(candidates, key=version) if candidates else None


def run_aapt2(tool: str, *args: str) -> tuple[str | None, str]:
    """aapt2's output, or None and why not."""
    try:
        result = subprocess.run([tool, *args], capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.SubprocessError) as error:
        return None, f"{tool} {' '.join(args)}: {error}"
    if result.returncode != 0:
        return None, f"{tool} {' '.join(args)} exited {result.returncode}: {result.stderr.strip()[-500:]}"
    return result.stdout, ""


def check_apk(references: list[tuple[str, str]], apk: str | None = None, aapt2: str | None = None) -> tuple[list[str], list[str]]:
    """M8: what the release APK ships for each @xml file the manifest points at, read back with aapt2 and checked as
    the sources are — in every configuration it ships: a library's file of the same name and qualifiers loses to the
    app's, but a qualified variant the app lacks (xml-v31/…) or a release build type's copy ships too, and Android
    takes it where it applies. The default configuration must be among them (Android 10, the minimum, needs it)."""
    apks = [apk] if apk else sorted(glob.glob("app/build/outputs/apk/release/*.apk"))
    if len(apks) != 1:
        return [f"expected one release APK, found {apks}; run assembleRelease first"], []
    tool = aapt2 or find_aapt2()
    if not tool:
        return ["aapt2 not found under $ANDROID_HOME or $ANDROID_SDK_ROOT (build-tools): the release APK can't be checked"], []
    dump, failure = run_aapt2(tool, "dump", "resources", apks[0])
    if dump is None:
        return [failure], []
    files = packaged_files(dump)
    problems: list[str] = []
    shipped: list[str] = []
    for attribute, name in references:
        found = files.get(f"xml/{name}")
        if not found:
            near = [line.strip() for line in dump.splitlines() if name in line][:5]
            problems.append(f"the release APK lists no file for @xml/{name} (aapt2 dump resources; lines naming it: {near})")
            continue
        configurations = [configuration or "default" for configuration, _ in found]
        if "default" not in configurations:
            problems.append(f"the release APK ships @xml/{name} only in the configurations {configurations}, not the default")
        for configuration, path in found:
            where = f"{os.path.basename(apks[0])}:{path} (@xml/{name}, {configuration or 'default'})"
            tree, failure = run_aapt2(tool, "dump", "xmltree", "--file", path, apks[0])
            root = xmltree_root(tree) if tree is not None else None
            if root is None:
                problems.append(failure or f"{where}: aapt2 printed no element tree: {(tree or '').strip()[:300]!r}")
                continue
            problems += check_packaged_file(attribute, root, where)
        shipped.append(f"@xml/{name} {configurations}")
    tool_version = os.path.basename(os.path.dirname(tool))
    facts = [f"release APK {apks[0]} (aapt2 {tool_version}), each file read back and checked: " + "; ".join(shipped)]
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
    apk_problems, apk_facts = check_apk(xml_references(path), argv[2] if len(argv) > 2 else None)
    problems += apk_problems
    facts += apk_facts
    # The manifest comes from the code under test: print it with workflow commands off.
    token = secrets.token_hex(16)
    print(f"::stop-commands::{token}")
    print(f"Release manifest {path}")
    print("\n".join(f"  {fact}" for fact in facts))
    print("\n".join(f"PROBLEM {problem}" for problem in problems) or "OK: permissions, backups, debuggable, cleartext, trust, exports, packaged files")
    print(f"::{token}::")
    if problems:
        print(f"::error title=release manifest::{escape(chr(10).join(problems))}")
        return 1
    # Also as a notice, so the evidence can be read through the API like the other build facts.
    print(f"::notice title=release manifest OK::{escape(chr(10).join(facts))}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
