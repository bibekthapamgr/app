"""Tests of scripts/licences.py (M9) on a synthetic Gradle dependency tree, POMs and engine facts: the notices list what
the release build ships, and anything not reviewed fails instead of being guessed.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import licences  # noqa: E402

TREE = """
releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core:designsystem
|    +--- androidx.compose:compose-bom:2025.01.00
|    +--- androidx.compose.ui:ui -> 1.7.6
|    |    \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.1.0} -> 2.1.0
|    \\--- com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
""".splitlines()

FACTS = """AAR build/pfengine.aar 123 bytes sha256 abc
BUILT WITH go version go1.27.3 linux/amd64, gomobile v0.0.0-20260908204917-8b95e45f8d3e, NDK 28.2.13676358, -androidapi 29
MODULES linked into libgojni.so (go version -m):
  github.com/google/btree v1.1.2
  golang.org/x/net v0.60.0
  gvisor.dev/gvisor v0.0.0-20261029062900-94fcb330700e
""".splitlines()


def pom_xml(licences_xml: str = "", parent: str = "") -> bytes:
    return f"""<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  {parent}
  <licenses>{licences_xml}</licenses>
</project>""".encode()


APACHE = "<license><name>The Apache Software License, Version 2.0</name><url>http://www.apache.org/licenses/LICENSE-2.0.txt</url></license>"
GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo1.maven.org/maven2/"

POMS = {
    GOOGLE + "androidx/compose/ui/ui/1.7.6/ui-1.7.6.pom": pom_xml(APACHE),
    CENTRAL + "org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.pom": pom_xml(
        "<license><name>The Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license>"
    ),
    # No licence of its own: its parent's.
    CENTRAL + "com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/"
    "listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.pom": pom_xml(
        parent="<parent><groupId>com.google.guava</groupId><artifactId>guava-parent</artifactId><version>26.0-android</version></parent>"
    ).replace(b"<licenses></licenses>", b""),
    CENTRAL + "com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom": pom_xml(
        "<license><name>Apache License, Version 2.0</name><url>http://www.apache.org/licenses/LICENSE-2.0.txt</url></license>"
    ),
}


def fetch(poms: dict[str, bytes]):
    asked: list[str] = []

    def get(url: str) -> bytes | None:
        asked.append(url)
        return poms.get(url)

    get.asked = asked  # type: ignore[attr-defined]
    return get


class NoticesTest(unittest.TestCase):
    def test_every_library_and_engine_module_is_listed_with_its_declared_licence(self) -> None:
        document, problems = licences.notices(TREE, FACTS, fetch(POMS))
        self.assertEqual([], problems)
        names = {(e["name"], e["version"], e["licence"]) for e in document["libraries"]}
        self.assertEqual(
            {
                ("androidx.compose.ui:ui", "1.7.6", "Apache-2.0"),
                ("org.jetbrains.kotlin:kotlin-stdlib", "2.1.0", "Apache-2.0"),
                ("com.google.guava:listenablefuture", "9999.0-empty-to-avoid-conflict-with-guava", "Apache-2.0"),
                ("Go standard library and runtime", "go1.27.3", "BSD-3-Clause-Go"),
                ("github.com/google/btree", "v1.1.2", "Apache-2.0"),
                ("golang.org/x/net", "v0.60.0", "BSD-3-Clause-Go"),
                ("gvisor.dev/gvisor", "v0.0.0-20261029062900-94fcb330700e", "gVisor"),
            },
            names,
        )
        self.assertNotIn("androidx.compose:compose-bom", {e["name"] for e in document["libraries"]}, "a BOM ships no code")
        self.assertEqual({"Apache-2.0", "BSD-3-Clause-Go", "gVisor"}, set(document["licences"]))

    def test_every_licence_named_has_its_text_in_the_app(self) -> None:
        assets = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "feature", "about", "src", "main", "assets")
        for spdx, licence in licences.LICENCES.items():
            path = os.path.join(assets, licence["text"])
            self.assertTrue(os.path.isfile(path), f"{spdx}: {path}")
            with open(path, encoding="utf-8") as f:
                text = f.read()
            self.assertGreater(len(text), 1000, spdx)
        with open(os.path.join(assets, licences.LICENCES["BSD-3-Clause-Go"]["text"]), encoding="utf-8") as f:
            self.assertIn("Copyright 2009 The Go Authors.", f.read())
        with open(os.path.join(assets, licences.LICENCES["BSD-3-Clause-Protobuf"]["text"]), encoding="utf-8") as f:
            self.assertIn("Copyright 2008 Google Inc.", f.read())
        with open(os.path.join(assets, licences.LICENCES["gVisor"]["text"]), encoding="utf-8") as f:
            gvisor = f.read()
        self.assertIn("Apache License", gvisor)
        self.assertIn("Permission is hereby granted, free of charge", gvisor, "the MIT licence some files carry")

    def test_a_licence_the_app_has_no_text_for_fails(self) -> None:
        poms = dict(POMS)
        poms[GOOGLE + "androidx/compose/ui/ui/1.7.6/ui-1.7.6.pom"] = pom_xml(
            "<license><name>GNU Lesser General Public License, Version 2.1</name><url>https://www.gnu.org/licenses/lgpl-2.1</url></license>"
        )
        _, problems = licences.notices(TREE, FACTS, fetch(poms))
        self.assertEqual(1, len(problems))
        self.assertIn("androidx.compose.ui:ui:1.7.6", problems[0])

    def test_a_library_that_declares_nothing_or_has_no_pom_fails(self) -> None:
        poms = dict(POMS)
        poms[GOOGLE + "androidx/compose/ui/ui/1.7.6/ui-1.7.6.pom"] = pom_xml().replace(b"<licenses></licenses>", b"")
        del poms[CENTRAL + "org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.pom"]
        _, problems = licences.notices(TREE, FACTS, fetch(poms))
        self.assertEqual(2, len(problems), problems)
        self.assertTrue(any("declares no licence" in p for p in problems))
        self.assertTrue(any("no POM for org.jetbrains.kotlin:kotlin-stdlib:2.1.0" in p for p in problems))

    def test_an_engine_module_not_reviewed_fails(self) -> None:
        facts = FACTS + ["  example.com/new/dependency v1.0.0"]
        _, problems = licences.notices(TREE, facts, fetch(POMS))
        self.assertEqual(["the engine links example.com/new/dependency v1.0.0, which docs/m4/dependency-review.md hasn't reviewed"], problems)
        _, problems = licences.notices(TREE, FACTS[:1], fetch(POMS))
        self.assertEqual(2, len(problems), "neither the toolchain nor the modules can be read")

    def test_the_engines_own_module_is_not_a_notice(self) -> None:
        # What gomobile's bind links: the engine as a dependency of its generated main package, built from the checkout.
        facts = FACTS + ["  github.com/bibekthapamgr/app/engine v0.0.0-00010101000000-000000000000", "  /home/runner/work/app/app/engine (devel)"]
        document, problems = licences.notices(TREE, facts, fetch(POMS))
        self.assertEqual([], problems)
        names = {e["name"] for e in document["libraries"]}
        self.assertNotIn("github.com/bibekthapamgr/app/engine", names)
        self.assertFalse(any(name.startswith("/") for name in names))

    def test_another_module_built_from_a_local_directory_fails(self) -> None:
        facts = FACTS + ["  golang.org/x/sys v0.48.0", "  /home/runner/work/sys (devel)"]
        _, problems = licences.notices(TREE, facts, fetch(POMS))
        self.assertEqual(
            ["the engine links golang.org/x/sys from the directory /home/runner/work/sys, which docs/m4/dependency-review.md hasn't reviewed"],
            problems,
        )

    def test_datastores_repackaged_protobuf_ships_protobufs_own_licence(self) -> None:
        tree = TREE + ["\\--- androidx.datastore:datastore-preferences-external-protobuf:1.3.0"]
        document, problems = licences.notices(tree, FACTS, fetch(POMS))
        self.assertEqual([], problems)
        self.assertIn(
            ("androidx.datastore:datastore-preferences-external-protobuf", "1.3.0", "BSD-3-Clause-Protobuf"),
            {(e["name"], e["version"], e["licence"]) for e in document["libraries"]},
        )
        self.assertIn("BSD-3-Clause-Protobuf", document["licences"])

    def test_names_and_urls_of_apache_2(self) -> None:
        for name in ("Apache-2.0", "Apache 2.0", "Apache License, Version 2.0", "The Apache Software License, Version 2.0"):
            self.assertEqual("Apache-2.0", licences.licence_id(name, ""), name)
        self.assertEqual("Apache-2.0", licences.licence_id("ASF 2.0 licence", "https://www.apache.org/licenses/LICENSE-2.0"))
        self.assertIsNone(licences.licence_id("Apache License 1.1", "https://www.apache.org/licenses/LICENSE-1.1"))
        self.assertIsNone(licences.licence_id("MIT", "https://opensource.org/licenses/MIT"))

    def test_check_fails_and_rewrites_the_file_when_it_differs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tree, facts, out = (os.path.join(tmp, n) for n in ("tree.txt", "facts.txt", "notices.json"))
            with open(tree, "w") as f:
                f.write("\n".join(TREE))
            with open(facts, "w") as f:
                f.write("\n".join(FACTS))
            document, _ = licences.notices(TREE, FACTS, fetch(POMS))
            with open(out, "w") as f:
                f.write("stale\n")
            with redirect_stdout(io.StringIO()) as printed:
                status = licences.main([tree, facts, "--check", out], fetch=fetch(POMS))
            self.assertEqual(1, status)
            self.assertIn("Commit the file", printed.getvalue())
            with open(out) as f:
                self.assertEqual(document, json.load(f), "rewritten, for CI to publish")
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, licences.main([tree, facts, "--check", out], fetch=fetch(POMS)), "now it matches")
            # A problem fails the check without writing anything.
            with redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(1, licences.main([tree, facts, "--check", out], fetch=fetch({})))
            self.assertIn("no POM for", printed.getvalue())


if __name__ == "__main__":
    unittest.main()
