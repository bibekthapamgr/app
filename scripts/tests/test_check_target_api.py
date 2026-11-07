"""Tests of check_target_api.py against the committed rules and a fake targetSdk: nothing due today; a warning inside
the lead time; a failure past the date; a stale reading warned about.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import datetime as dt
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import check_target_api as cta  # noqa: E402

RULES = {
    "read_on": "2026-11-07",
    "source": "https://example.test/rules",
    "requirements": [
        {"api": 36, "android": "16", "new_apps_and_updates_from": "2026-08-31", "extension_until": "2026-11-01"},
        {"api": 35, "android": "15", "new_apps_and_updates_from": "2025-08-31", "extension_until": "2025-11-01"},
    ],
    "expected_next": {"api": 37, "android": "17", "new_apps_and_updates_from": "2027-08-31"},
}


class TargetApi(unittest.TestCase):
    def test_the_committed_tree_is_in_order_today(self) -> None:
        failures, warnings = cta.check(dt.date(2026, 11, 8), 120, 180)
        self.assertEqual(failures, [])
        self.assertEqual(warnings, [])

    def test_a_passed_requirement_fails(self) -> None:
        with mock.patch.object(cta, "rules", lambda: RULES), mock.patch.object(cta, "target_sdk", lambda: 35):
            failures, _ = cta.check(dt.date(2026, 11, 8), 120, 180)
        self.assertTrue(any("required targetSdk 36" in f for f in failures), failures)

    def test_a_requirement_inside_the_lead_time_warns(self) -> None:
        with mock.patch.object(cta, "rules", lambda: RULES), mock.patch.object(cta, "target_sdk", lambda: 35):
            failures, warnings = cta.check(dt.date(2026, 6, 1), 120, 365)
        self.assertEqual(failures, [])
        self.assertTrue(any("from 2026-08-31, in 91 days" in w for w in warnings), warnings)

    def test_the_expected_next_warns_before_and_after_its_date(self) -> None:
        with mock.patch.object(cta, "rules", lambda: RULES):
            _, before = cta.check(dt.date(2027, 6, 1), 120, 9999)
            _, after = cta.check(dt.date(2027, 9, 10), 120, 9999)
        self.assertTrue(any("expected to be required from 2027-08-31, in 91 days" in w for w in before), before)
        self.assertTrue(any("10 days ago" in w for w in after), after)

    def test_an_extension_turns_the_failure_into_a_warning_until_it_ends(self) -> None:
        # Review A D-1: extension_until is honoured only when the owner says Google granted it (--extended).
        with mock.patch.object(cta, "rules", lambda: RULES), mock.patch.object(cta, "target_sdk", lambda: 35):
            failures, warnings = cta.check(dt.date(2026, 10, 1), 120, 180, extended=True)
            self.assertEqual(failures, [])
            self.assertTrue(any("extension that ends 2026-11-01, in 31 days" in w for w in warnings), warnings)
            failures, _ = cta.check(dt.date(2026, 11, 2), 120, 180, extended=True)
            self.assertTrue(any("required targetSdk 36" in f for f in failures), failures)
            failures, _ = cta.check(dt.date(2026, 10, 1), 120, 180)
            self.assertTrue(any('set "extension_granted": true' in f for f in failures), failures)

    def test_a_granted_extension_in_the_rules_file_is_honoured_without_a_flag(self) -> None:
        # Re-review A RA-9 / C RC-7: CI never passes --extended; the owner sets extension_granted in the rules file.
        rules = {**RULES, "requirements": [dict(RULES["requirements"][0], extension_granted=True), RULES["requirements"][1]]}
        with mock.patch.object(cta, "rules", lambda: rules), mock.patch.object(cta, "target_sdk", lambda: 35):
            failures, warnings = cta.check(dt.date(2026, 10, 1), 120, 180)
            self.assertEqual(failures, [])
            self.assertTrue(any("extension that ends 2026-11-01" in w for w in warnings), warnings)
            failures, _ = cta.check(dt.date(2026, 11, 2), 120, 180)
            self.assertTrue(any("required targetSdk 36" in f for f in failures), failures)

    def test_an_expected_next_already_listed_is_flagged_instead_of_warning_twice(self) -> None:
        # Review A D-2: once API 37 is in the requirements, expected_next must move to 38.
        rules = dict(RULES, requirements=RULES["requirements"] + [{"api": 37, "android": "17", "new_apps_and_updates_from": "2027-08-31"}])
        with mock.patch.object(cta, "rules", lambda: rules), mock.patch.object(cta, "target_sdk", lambda: 37):
            failures, warnings = cta.check(dt.date(2027, 6, 1), 120, 9999)
        self.assertEqual(failures, [])
        self.assertEqual(len(warnings), 1, warnings)
        self.assertIn("set it to 38", warnings[0])

    def test_a_stale_reading_warns(self) -> None:
        with mock.patch.object(cta, "rules", lambda: RULES):
            _, warnings = cta.check(dt.date(2027, 6, 1), 1, 180)
        self.assertTrue(any("re-read" in w for w in warnings), warnings)


if __name__ == "__main__":
    unittest.main()
