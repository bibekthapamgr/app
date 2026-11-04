#!/usr/bin/env python3
"""Builds the app's open-source notices from what the release build ships, M9 (About → Open-source licences).

Reads the release runtime classpath as Gradle prints it (`./gradlew -q :app:dependencies --configuration
releaseRuntimeClasspath`, the tree scripts/osv_scan.py reads) and the engine's facts (engine/build/pfengine-facts.txt,
written by engine/build-aar.sh: the Go toolchain it was built with and the modules linked into libgojni.so, read from
the library itself with `go version -m`). For each Maven library it reads the licence its POM declares (or its parent
POM's), from Google's Maven repository or Maven Central; each Go module's licence comes from GO_MODULES below, the
review of docs/m4/dependency-review.md.

Only licences whose full text the app ships (LICENCES, in feature/about/src/main/assets/licences/) are accepted: a
library whose POM declares anything else, or nothing, or a Go module not reviewed here, fails the build until it is
reviewed — a notice is never guessed. OVERRIDES names a library whose POM is wrong or silent, with the reason.

Usage: licences.py <gradle dependencies output> <engine facts> --out FILE   (writes the notices)
       licences.py <gradle dependencies output> <engine facts> --check FILE (fails, and rewrites FILE, if it differs)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree
from typing import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osv_scan import read_tree  # noqa: E402

REPOSITORIES = ("https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2")

# The licences whose full text the app ships, by the id the notices use.
LICENCES = {
    "Apache-2.0": {"title": "Apache License 2.0", "text": "licences/Apache-2.0.txt"},
    # Go's licence, with its copyright line: the Go toolchain and standard library, and the golang.org/x modules.
    "BSD-3-Clause-Go": {"title": "BSD 3-Clause License (The Go Authors)", "text": "licences/BSD-3-Clause-Go.txt"},
    # gVisor's LICENSE file as it is at the pinned commit: Apache 2.0, and the MIT and BSD licences some of its files carry.
    "gVisor": {"title": "gVisor licence (Apache License 2.0; some files MIT or BSD)", "text": "licences/gVisor.txt"},
}

# How POMs name the licences above (compared lower-case, without punctuation runs), and their URLs.
NAMES = {
    "Apache-2.0": {
        "apache 2.0",
        "apache-2.0",
        "apache license 2.0",
        "apache license version 2.0",
        "apache software license version 2.0",
        "the apache license version 2.0",
        "the apache software license version 2.0",
        "apache license v2.0",
        "asl 2.0",
    },
}
URLS = {
    "Apache-2.0": re.compile(r"^https?://(www\.)?(apache\.org/licenses/LICENSE-2\.0(\.txt|\.html)?|opensource\.org/licenses/Apache-2\.0)/?$"),
}

# The Go modules linked into libgojni.so, as reviewed in docs/m4/dependency-review.md. A module not here fails.
GO_MODULES = {
    "github.com/google/btree": "Apache-2.0",
    "golang.org/x/exp": "BSD-3-Clause-Go",
    "golang.org/x/mobile": "BSD-3-Clause-Go",
    "golang.org/x/net": "BSD-3-Clause-Go",
    "golang.org/x/sys": "BSD-3-Clause-Go",
    "golang.org/x/time": "BSD-3-Clause-Go",
    "gvisor.dev/gvisor": "gVisor",
}

# group:artifact -> (licence id, why the POM isn't taken as it is). Empty: every POM so far declares its own licence.
OVERRIDES: dict[str, tuple[str, str]] = {}

Fetch = Callable[[str], "bytes | None"]


def fetch_url(url: str) -> bytes | None:
    """The body at url, None when it isn't there (404); anything else fails, after three tries."""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "proxyflow-licences"}), timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 * (attempt + 1))
    return None


def pom(group: str, artifact: str, version: str, fetch: Fetch) -> ElementTree.Element | None:
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    for repository in REPOSITORIES:
        body = fetch(f"{repository}/{path}")
        if body is not None:
            root = ElementTree.fromstring(body)
            # Drop the namespace, so the same paths work for POMs written with and without it.
            for element in root.iter():
                element.tag = element.tag.split("}", 1)[-1]
            return root
    return None


def declared_licences(group: str, artifact: str, version: str, fetch: Fetch, depth: int = 0) -> list[tuple[str, str]]:
    """(name, url) of each licence the POM declares, or its nearest parent's; empty when none does."""
    root = pom(group, artifact, version, fetch)
    if root is None:
        raise LookupError(f"no POM for {group}:{artifact}:{version} in {', '.join(REPOSITORIES)}")
    found = [
        ((lic.findtext("name") or "").strip(), (lic.findtext("url") or "").strip())
        for lic in root.findall("./licenses/license")
    ]
    if found or depth >= 5:
        return found
    parent = root.find("./parent")
    if parent is None:
        return []
    return declared_licences(
        parent.findtext("groupId", "").strip(),
        parent.findtext("artifactId", "").strip(),
        parent.findtext("version", "").strip(),
        fetch,
        depth + 1,
    )


def licence_id(name: str, url: str) -> str | None:
    """The id in LICENCES a POM's licence names, by its name or its URL; None for any other licence."""
    plain = re.sub(r"[^a-z0-9.+-]+", " ", name.lower()).strip()
    for spdx, names in NAMES.items():
        if plain in names:
            return spdx
    for spdx, pattern in URLS.items():
        if pattern.match(url):
            return spdx
    return None


def maven_entries(lines: list[str], fetch: Fetch) -> tuple[list[dict], list[str]]:
    libraries, unread = read_tree(lines)
    entries: list[dict] = []
    problems = list(unread)
    for coordinates, version in sorted(libraries.items()):
        group, artifact = coordinates.split(":")
        if artifact.endswith("-bom"):
            continue  # a platform: versions only, no code in the app
        if coordinates in OVERRIDES:
            entries.append({"name": coordinates, "version": version, "licence": OVERRIDES[coordinates][0]})
            continue
        try:
            declared = declared_licences(group, artifact, version, fetch)
        except LookupError as e:
            problems.append(str(e))
            continue
        ids = {licence_id(name, url) for name, url in declared}
        if not declared:
            problems.append(f"{coordinates}:{version} declares no licence: review it and add it to OVERRIDES")
        elif None in ids or len(ids) != 1:
            problems.append(f"{coordinates}:{version} declares {declared}: not a licence the app ships the text of; review it")
        else:
            entries.append({"name": coordinates, "version": version, "licence": ids.pop()})
    return entries, problems


def go_entries(facts: list[str]) -> tuple[list[dict], list[str]]:
    """The Go toolchain and the modules linked into libgojni.so, from build-aar.sh's facts."""
    entries: list[dict] = []
    problems: list[str] = []
    toolchain = next((m.group(1) for line in facts if (m := re.search(r"^BUILT WITH go version (go\S+)", line))), None)
    if toolchain is None:
        problems.append("the engine facts don't say which Go built the engine (BUILT WITH go version …)")
    else:
        entries.append({"name": "Go standard library and runtime", "version": toolchain, "licence": "BSD-3-Clause-Go"})
    try:
        start = next(i for i, line in enumerate(facts) if line.startswith("MODULES linked into libgojni.so"))
    except StopIteration:
        return entries, problems + ["the engine facts list no modules linked into libgojni.so"]
    for line in facts[start + 1:]:
        if not line.startswith("  "):
            break
        parts = line.split()
        if len(parts) < 2:
            problems.append(f"an engine module line that can't be read: {line.strip()}")
            continue
        module, version = parts[0], parts[1]
        if module not in GO_MODULES:
            problems.append(f"the engine links {module} {version}, which docs/m4/dependency-review.md hasn't reviewed")
            continue
        entries.append({"name": module, "version": version, "licence": GO_MODULES[module]})
    return entries, problems


def notices(classpath: list[str], facts: list[str], fetch: Fetch = fetch_url) -> tuple[dict, list[str]]:
    maven, maven_problems = maven_entries(classpath, fetch)
    go, go_problems = go_entries(facts)
    libraries = sorted(maven + go, key=lambda e: (e["licence"], e["name"].lower()))
    used = sorted({e["licence"] for e in libraries})
    document = {
        "about": "Generated by scripts/licences.py from the release runtime classpath and the modules linked into the "
        "engine; checked in CI against the release build. Do not edit.",
        "licences": {spdx: LICENCES[spdx] for spdx in used},
        "libraries": libraries,
    }
    problems = maven_problems + go_problems
    if not maven:
        problems.append("no library read from the release runtime classpath")
    return document, problems


def render(document: dict) -> str:
    return json.dumps(document, indent=1, ensure_ascii=False, sort_keys=False) + "\n"


def main(argv: list[str] | None = None, fetch: Fetch = fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("classpath")
    parser.add_argument("facts")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--out")
    mode.add_argument("--check")
    args = parser.parse_args(argv)
    with open(args.classpath, encoding="utf-8") as f:
        classpath = f.read().splitlines()
    with open(args.facts, encoding="utf-8") as f:
        facts = f.read().splitlines()
    document, problems = notices(classpath, facts, fetch)
    for problem in problems:
        print(f"::error title=Open-source notices::{problem}")
    if problems:
        return 1
    text = render(document)
    target = args.out or args.check
    if args.check:
        try:
            with open(target, encoding="utf-8") as f:
                committed = f.read()
        except FileNotFoundError:
            committed = None
        if committed != text:
            os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(text)
            print(
                f"::error file={target},title=Open-source notices::The notices are not the release build's libraries "
                "(a library or a version changed). Commit the file from ci-screenshots/<branch>."
            )
            return 1
        print(f"::notice title=Open-source notices::{len(document['libraries'])} libraries, as the release build ships them")
        return 0
    with open(target, "w", encoding="utf-8") as f:
        f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
