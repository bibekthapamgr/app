"""Tests of adb_install in scripts/adb_reboot.sh (M12-80): an install that adb refuses with "device offline" is tried
again once the device answers; one that never installs fails after three tries. Run with a fake adb on PATH.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HELPERS = os.path.join(HERE, "..", "adb_reboot.sh")

FAKE_ADB = """#!/bin/bash
# "install": fails with "device offline" the first OFFLINE_TRIES times, then installs; logs every call.
echo "$*" >> "$FAKE_LOG"
case "$1" in
  install)
    n=$(( $(grep -c '^install' "$FAKE_LOG") ))
    if [ "$n" -le "${OFFLINE_TRIES:-0}" ]; then echo "adb: device offline" >&2; exit 1; fi
    echo "Success"; exit 0;;
  wait-for-device) exit 0;;
  *) exit 0;;
esac
"""


class AdbInstall(unittest.TestCase):
    def run_install(self, offline_tries: int) -> tuple[int, list[str], str]:
        with tempfile.TemporaryDirectory() as tmp:
            adb = os.path.join(tmp, "adb")
            with open(adb, "w") as f:
                f.write(FAKE_ADB)
            os.chmod(adb, 0o755)
            log = os.path.join(tmp, "install.txt")
            calls = os.path.join(tmp, "calls.txt")
            open(calls, "w").close()
            env = {
                **os.environ,
                "PATH": tmp + os.pathsep + os.environ["PATH"],
                "FAKE_LOG": calls,
                "OFFLINE_TRIES": str(offline_tries),
                "ADB_INSTALL_PAUSE": "0",
            }
            done = subprocess.run(
                ["bash", "-c", f'. "{HELPERS}"; adb_install app.apk "{log}" -g'],
                env=env,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            with open(calls) as f:
                made = [line.strip() for line in f if line.strip()]
            with open(log) as f:
                logged = f.read()
        return done.returncode, made, logged

    def test_an_install_the_device_was_offline_for_is_tried_again(self) -> None:
        code, calls, log = self.run_install(offline_tries=1)
        self.assertEqual(code, 0)
        self.assertEqual([c for c in calls if c.startswith("install")], ["install -r -t -g app.apk"] * 2)
        self.assertIn("wait-for-device", calls)
        self.assertIn("failed (try 1): adb: device offline; waiting for the device", log)

    def test_a_device_that_answers_installs_once(self) -> None:
        code, calls, _ = self.run_install(offline_tries=0)
        self.assertEqual(code, 0)
        self.assertEqual(sum(1 for c in calls if c.startswith("install")), 1)
        self.assertNotIn("wait-for-device", calls)

    def test_an_install_that_never_succeeds_fails_after_three_tries(self) -> None:
        code, calls, log = self.run_install(offline_tries=9)
        self.assertEqual(code, 1)
        self.assertEqual(sum(1 for c in calls if c.startswith("install")), 3)
        self.assertTrue(log.rstrip().endswith("adb: device offline"), log)


if __name__ == "__main__":
    unittest.main()
