#!/usr/bin/env python3
"""Collect the errors of a failed CI run into a short digest.

GitHub serves Actions logs and artifacts from blob storage, which some build
environments cannot reach (including the cloud workspace this project is
developed in). Annotations are served by api.github.com instead, so this
script prints the digest and also emits it as workflow annotations that can
be read with:

    GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations

Inputs: the Gradle console logs passed as arguments, plus the JUnit XML and
Android Lint XML reports found under the current directory. Payloads and
credentials never reach these files: the app has no network code yet and
the tests use generated test data only (random usernames and passwords that
exist only for the run).
"""

import glob
import os
import re
import secrets
import sys
import xml.etree.ElementTree as ET

MAX_CHUNK = 3500  # characters per annotation
LEVELS = ("error", "warning", "notice")  # GitHub keeps 10 of each per step
MAX_TESTS = 40
SCREEN_DUMP = 12000  # characters of a failing UI test's screen description
ROOT = os.getcwd().rstrip("/") + "/"


def short(text: str) -> str:
    return text.replace("file://", "").replace(ROOT, "")


def gradle_errors(path: str) -> list[str]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    name = os.path.basename(path)
    found: list[str] = []
    seen: set[str] = set()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("* What went wrong:"):
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith(("* Try:", "* Exception is:", "* Get more help")):
                if lines[i].strip():
                    block.append(short(lines[i].rstrip()))
                i += 1
            text = "\n".join(block[:60])
            if text not in seen:
                seen.add(text)
                found.append(f"[{name}] What went wrong:\n{text}")
            continue
        if line.startswith("e: ") or "ktlint(" in line:
            text = short(line.strip())
            if text not in seen:
                seen.add(text)
                found.append(f"[{name}] {text}")
        i += 1
    if not found and not any("BUILD SUCCESSFUL" in line for line in lines):
        # Failures outside Gradle's own reporting (wrapper download, JVM start): keep the end of the log.
        tail = [short(line.rstrip()) for line in lines if line.strip()][-30:]
        found.append(f"[{name}] last lines:\n" + "\n".join(tail))
    return found


# Frames that say where a failure came from, after the first lines of a stack.
ORIGIN = re.compile(r"(Caused by|Suppressed:|app\.proxyflow|kotlinx\.coroutines|androidx\.(compose|lifecycle|activity|datastore|room))")
NOISE = re.compile(r"(jdk\.internal\.reflect|java\.lang\.reflect|org\.robolectric\.shadows|\$\$Reflector|org\.junit\.|sun\.reflect)")


def condensed_stack(lines: list[str], head: int = 12, origin: int = 30) -> list[str]:
    """The first lines of a stack trace, then the frames that show its origin
    (the app's own code, coroutines, Compose, Lifecycle, DataStore, Room and
    every "Caused by"), skipping reflection and Robolectric shadow frames."""
    clean: list[str] = []
    for line in lines:
        if not NOISE.search(line) and (not clean or clean[-1] != line):
            clean.append(line)  # consecutive repeats (recursive frames) collapse to one
    out = clean[:head]
    picked = [line for line in clean[head:] if ORIGIN.search(line)]
    if picked:
        out.append(f"    ... ({len(clean) - head} more frames; origin frames:)")
        out += picked[:origin]
    elif len(clean) > head:
        out.append(f"    ... ({len(clean) - head} more frames)")
    return out


def test_failures() -> list[str]:
    found: list[str] = []
    seen_messages: set[str] = set()
    # JVM and Robolectric tests, then instrumented tests run on an emulator (connected*AndroidTest).
    reports = sorted(glob.glob("**/build/test-results/**/*.xml", recursive=True))
    reports += sorted(glob.glob("**/build/outputs/androidTest-results/**/*.xml", recursive=True))
    for report in reports:
        try:
            tree = ET.parse(report)
        except ET.ParseError:
            continue
        for case in tree.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                full_message = short((problem.get("message") or "").strip())
                if "--- screen ---" in full_message:
                    # A UI test's description of the screen (see core/testing DumpOnFailureRule).
                    found.append(f"TEST {case.get('classname')}.{case.get('name')}: {full_message[:SCREEN_DUMP]}")
                    continue
                message = full_message[:600]
                body = short((problem.text or "").strip()).splitlines()
                key = message or "\n".join(body[:2])
                if key in seen_messages:
                    found.append(f"TEST {case.get('classname')}.{case.get('name')}: same failure as above")
                    continue
                seen_messages.add(key)
                detail = "\n".join(condensed_stack(body))
                found.append(f"TEST {case.get('classname')}.{case.get('name')}: {message}\n{detail}")
    if len(found) > MAX_TESTS:
        found = found[:MAX_TESTS] + [f"... {len(found) - MAX_TESTS} more test failures"]
    return found


def lint_issues() -> list[str]:
    found: list[str] = []
    for report in sorted(glob.glob("**/build/reports/lint-results-*.xml", recursive=True)):
        try:
            tree = ET.parse(report)
        except ET.ParseError:
            continue
        for issue in tree.iter("issue"):
            location = issue.find("location")
            where = ""
            if location is not None:
                where = f"{short(location.get('file', ''))}:{location.get('line', '')}"
            found.append(f"LINT {issue.get('severity')} {issue.get('id')} {where} {issue.get('message')}")
    return found


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def chunks_of(items: list[str]) -> list[str]:
    chunks: list[str] = []
    current = ""
    for item in items:
        piece = item if len(item) <= MAX_CHUNK else item[: MAX_CHUNK - 20] + "\n...(cut)"
        if current and len(current) + len(piece) + 2 > MAX_CHUNK:
            chunks.append(current)
            current = piece
        else:
            current = piece if not current else current + "\n\n" + piece
    if current:
        chunks.append(current)
    capacity = 10 * len(LEVELS)
    if len(chunks) > capacity:
        omitted = len(chunks) - capacity
        chunks = chunks[:capacity]
        chunks[-1] += f"\n\n... {omitted} more part(s) omitted"
    return chunks


def main(logs: list[str]) -> None:
    items: list[str] = []
    for log in logs:
        items += gradle_errors(log)
    items += test_failures()
    items += lint_issues()
    if not items:
        items = ["No errors found in the Gradle logs or the test and Lint reports."]
    # The digest quotes logs and test output, which come from the code under test:
    # print them with workflow commands off, so none of that text is run as one.
    token = secrets.token_hex(16)
    print(f"::stop-commands::{token}")
    print("\n\n".join(items))
    print(f"::{token}::")
    chunks = chunks_of(items)
    for n, chunk in enumerate(chunks):
        print(f"::{LEVELS[n // 10]} title=digest {n + 1}/{len(chunks)}::{escape(chunk)}")


if __name__ == "__main__":
    main(sys.argv[1:])
