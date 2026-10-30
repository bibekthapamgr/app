#!/usr/bin/env python3
"""Summarise what a CI run built and tested, as notice annotations.

Evidence for the milestone acceptance reports that can be read through the
GitHub API (GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations) even
where Actions logs and artifacts cannot be downloaded: test counts per
module, APK sizes, the permissions of the merged manifests and every library
on the release runtime classpath.

Usage: ci_build_facts.py <output of ./gradlew :app:dependencies --configuration releaseRuntimeClasspath>
"""

import glob
import os
import re
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


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(dependency_report: str) -> None:
    lines = tests() + apks() + manifests() + dependencies(dependency_report)
    print("\n".join(lines))
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
    main(sys.argv[1])
