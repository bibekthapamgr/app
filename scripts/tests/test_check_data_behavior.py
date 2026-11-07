"""Tests of check_data_behavior.py: the lock matches the tree it was made from, a changed item is named, a removed one
too, and the fingerprinted items are the ones the policy review must cover.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import check_data_behavior as cdb  # noqa: E402


class DataBehaviour(unittest.TestCase):
    def test_the_committed_lock_matches_the_tree(self) -> None:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(cdb.check(), 0, out.getvalue())

    def test_the_items_cover_what_the_policy_describes(self) -> None:
        names = set(cdb.items())
        for wanted in ("manifest:app", "manifest:core/vpn", "network:preflight-target", "stored:room-schemas", "stored:datastore-keys", "stored:log-retention", "stored:log-contents", "sent:log-export", "stored:backup-and-network-xml", "says:privacy-strings", "says:privacy-policy-doc", "says:play-declarations", "libraries:version-catalog", "libraries:dependency-review"):
            self.assertIn(wanted, names)
        it = cdb.items()
        self.assertIn("connectivitycheck.gstatic.com", it["network:preflight-target"])
        self.assertIn("FOREGROUND_SERVICE_SYSTEM_EXEMPTED", it["manifest:core/vpn"])
        self.assertIn("privacy_sent_nothing=", it["says:privacy-strings"])
        self.assertIn("active_profile_id", it["stored:datastore-keys"])
        self.assertIn("MAX_ENTRIES=5_000", it["stored:log-retention"])
        # Review A C-2..C-4: backup and network-security configuration, what the log may hold and share, the libraries.
        self.assertIn('android:allowBackup="false"', it["manifest:app"])
        self.assertIn('android:networkSecurityConfig="@xml/network_security_config"', it["manifest:app"])
        self.assertIn("app/src/main/res/xml/backup_rules.xml ", it["stored:backup-and-network-xml"])
        self.assertIn("app/src/main/res/xml/network_security_config.xml ", it["stored:backup-and-network-xml"])
        self.assertIn("val ALLOWED = setOf(", it["stored:log-contents"])
        self.assertIn("object LogSanitizer", it["stored:log-contents"])
        self.assertNotIn("object LogExport", it["stored:log-contents"])
        self.assertIn("object LogExport", it["sent:log-export"])
        self.assertIn("fun line(", it["sent:log-export"])
        self.assertIn("[libraries]", it["libraries:version-catalog"])
        self.assertIn("androidx-room-runtime", it["libraries:version-catalog"])
        self.assertNotIn("[plugins]", it["libraries:version-catalog"])

    def test_a_version_bump_alone_does_not_break_the_lock(self) -> None:
        # Review A C-1 / B B-5: bump_version.py regenerates the policy's version line; that is not a data behaviour.
        real_read = cdb.read
        policy = real_read("docs/m9/privacy-policy.md")
        self.assertIn("version 1.0.0 (11)", policy)
        bumped = policy.replace("version 1.0.0 (11)", "version 1.0.1 (12)")
        cdb.read = lambda path: bumped if path == "docs/m9/privacy-policy.md" else real_read(path)  # type: ignore[assignment]
        try:
            self.assertEqual(cdb.items()["says:privacy-policy-doc"], cdb.items()["says:privacy-policy-doc"])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(cdb.check(), 0, out.getvalue())
        finally:
            cdb.read = real_read  # type: ignore[assignment]
        self.assertNotEqual(bumped, policy)

    def test_a_changed_or_removed_item_is_named(self) -> None:
        real_fp, real_load = cdb.fingerprints, cdb.load_lock
        lock = json.loads(json.dumps(real_load()))
        lock["items"]["network:preflight-target"] = "0" * 64
        lock["items"]["stored:old-thing"] = "1" * 64
        cdb.load_lock = lambda: lock  # type: ignore[assignment]
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                self.assertEqual(cdb.check(), 1)
        finally:
            cdb.load_lock = real_load  # type: ignore[assignment]
        text = out.getvalue()
        self.assertIn("PROBLEM changed: network:preflight-target", text)
        self.assertIn("PROBLEM removed: stored:old-thing", text)
        self.assertIn("docs/m12/policy-review.md", text)
        self.assertIs(cdb.fingerprints, real_fp)

    def test_update_needs_a_reviewer_and_a_note(self) -> None:
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(cdb.main(["--update"]), 2)


if __name__ == "__main__":
    unittest.main()
