#!/usr/bin/env python3
"""Summarise what a CI run built and tested, as notice annotations.

Evidence for the milestone acceptance reports that can be read through the
GitHub API (GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations) even
where Actions logs and artifacts cannot be downloaded: test counts per
module, APK sizes, the permissions of the merged manifests and every library
on the release runtime classpath.

Usage:
  ci_build_facts.py <output of ./gradlew :app:dependencies --configuration releaseRuntimeClasspath>
  ci_build_facts.py --device-tests <label>   (each instrumented test and its result, per emulator)
  ci_build_facts.py --go-tests <go test -json output>   (engine tests per package; failures as errors)
  ci_build_facts.py --engine-aar <engine/build/pfengine-facts.txt>   (what engine/build-aar.sh built)
  ci_build_facts.py --log-tail <log> <title>   (the end of a failed step's log, as error annotations)
"""

import glob
import json
import os
import re
import secrets
import sys
import xml.etree.ElementTree as ET

MAX_CHUNK = 3500  # characters per annotation; GitHub keeps 10 notices per step


def tests() -> list[str]:
    per_module: dict[str, list[int]] = {}
    for report in glob.glob("**/build/test-results/testDebugUnitTest/*.xml", recursive=True) + glob.glob("**/build/test-results/test/*.xml", recursive=True):
        module = report.split("/build/")[0] or "."
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        counts = per_module.setdefault(module, [0, 0, 0, 0])
        for i, key in enumerate(("tests", "failures", "errors", "skipped")):
            counts[i] += int(suite.get(key, 0))
    total = [sum(c[i] for c in per_module.values()) for i in range(4)]
    lines = [f"TESTS {total[0]} run, {total[1]} failed, {total[2]} errors, {total[3]} skipped"]
    lines += [f"  {module}: {c[0]} run, {c[1]} failed, {c[2]} errors, {c[3]} skipped" for module, c in sorted(per_module.items())]
    return lines


def apks() -> list[str]:
    return [f"APK {path} {os.path.getsize(path):,} bytes" for path in sorted(glob.glob("app/build/outputs/apk/*/*.apk"))]


def manifests() -> list[str]:
    lines = []
    for path in sorted(glob.glob("app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml")):
        text = open(path, encoding="utf-8").read()
        used = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', text)
        lines.append(f"MANIFEST {path}: uses-permission {used or 'none'}")
    return lines


def dependencies(path: str) -> list[str]:
    modules = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "project :" in line or line.rstrip().endswith("(c)") or line.rstrip().endswith("(n)"):
                continue  # own modules, constraints and unresolved entries are not libraries in the APK
            match = re.search(r"([\w.\-]+):([\w.\-]+)(?::([\w.\-+]+))?(?: -> ([\w.\-+]+))?", line)
            if match and (match.group(3) or match.group(4)) and "--- " in line:
                group, name, declared, resolved = match.groups()
                modules.add(f"{group}:{name}:{resolved or declared}")
    return [f"RELEASE RUNTIME CLASSPATH {len(modules)} libraries"] + [f"  {m}" for m in sorted(modules)]


def database_export() -> list[str]:
    """The profiles table as StoredProfilesTest exported it: the credential columns hold ciphertext only."""
    path = "core/data/build/reports/database-export/profiles.txt"
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as f:
        return ["DATABASE EXPORT (core/data StoredProfilesTest, Room on Robolectric)"] + [f"  {line}" for line in f.read().splitlines()]


def device_tests(label: str) -> list[str]:
    lines: list[str] = []
    for report in sorted(glob.glob("**/build/outputs/androidTest-results/connected/**/*.xml", recursive=True)):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for suite in [root] if root.tag == "testsuite" else list(root.iter("testsuite")):
            device = {p.get("name"): p.get("value") for p in suite.iter("property")}.get("device", "unknown device")
            lines.append(
                f"DEVICE TESTS {label} on {device}: {suite.get('tests')} run, {suite.get('failures')} failed, "
                f"{suite.get('errors')} errors, {suite.get('skipped')} skipped"
            )
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    status = "FAILED"
                elif case.find("skipped") is not None:
                    status = "skipped"
                else:
                    status = "passed"
                lines.append(f"  {case.get('classname')}.{case.get('name')}: {status} ({case.get('time')} s)")
    return lines or [f"DEVICE TESTS {label}: no results found"]


def go_tests(path: str) -> tuple[list[str], list[str]]:
    """Counts per package from `go test -json`, and the output of every failing test."""
    counts: dict[str, list[int]] = {}
    output: dict[tuple[str, str], list[str]] = {}
    failed: list[tuple[str, str]] = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            pkg, test, action = ev.get("Package", ""), ev.get("Test"), ev.get("Action")
            short_pkg = pkg.removeprefix("github.com/bibekthapamgr/app/")
            if test and action == "output":
                output.setdefault((short_pkg, test), []).append(ev.get("Output", ""))
            if test and "/" not in test and action in ("pass", "fail", "skip"):
                c = counts.setdefault(short_pkg, [0, 0, 0])
                c[("pass", "fail", "skip").index(action)] += 1
                if action == "fail":
                    failed.append((short_pkg, test))
            if not test and action == "fail" and short_pkg not in counts:
                counts.setdefault(short_pkg, [0, 0, 0])
                failed.append((short_pkg, "(package did not build or a test crashed)"))
    total = [sum(c[i] for c in counts.values()) for i in range(3)]
    lines = [f"GO TESTS {total[0]} passed, {total[1]} failed, {total[2]} skipped (top-level tests, race detector on)"]
    lines += [f"  {pkg}: {c[0]} passed, {c[1]} failed, {c[2]} skipped" for pkg, c in sorted(counts.items())]
    errors = []
    for pkg, test in failed:
        text = "".join(output.get((pkg, test), []))[-3000:]
        errors.append(f"{pkg} {test} FAILED\n{text}")
    return lines, errors


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def emit(lines: list[str]) -> None:
    # Names in these lines come from the build under test: print them with workflow commands off.
    token = secrets.token_hex(16)
    print(f"::stop-commands::{token}")
    print("\n".join(lines))
    print(f"::{token}::")
    chunks: list[str] = []
    current = ""
    for line in lines:
        if current and len(current) + len(line) + 1 > MAX_CHUNK:
            chunks.append(current)
            current = line
        else:
            current = line if not current else current + "\n" + line
    if current:
        chunks.append(current)
    for n, chunk in enumerate(chunks[:10]):
        print(f"::notice title=facts {n + 1}/{min(len(chunks), 10)}::{escape(chunk)}")


if __name__ == "__main__":
    if sys.argv[1] == "--device-tests":
        emit(device_tests(sys.argv[2]))
    elif sys.argv[1] == "--go-tests":
        summary, failures = go_tests(sys.argv[2])
        emit(summary)
        for failure in failures[:10]:
            print(failure)
            print(f"::error title=go test::{escape(failure[:MAX_CHUNK])}")
    elif sys.argv[1] == "--log-tail":
        with open(sys.argv[2], encoding="utf-8", errors="replace") as f:
            tail = f.read().splitlines()[-120:]
        chunks: list[str] = []
        current = ""
        for line in tail:
            if current and len(current) + len(line) + 1 > MAX_CHUNK:
                chunks.append(current)
                current = line
            else:
                current = line if not current else current + "\n" + line
        if current:
            chunks.append(current)
        for n, chunk in enumerate(chunks[-10:]):
            print(f"::error title={sys.argv[3]} log {n + 1}/{min(len(chunks), 10)}::{escape(chunk)}")
    elif sys.argv[1] == "--engine-aar":
        with open(sys.argv[2], encoding="utf-8", errors="replace") as f:
            emit([line.rstrip("\n") for line in f])
    else:
        emit(tests() + apks() + manifests() + database_export() + dependencies(sys.argv[1]))
