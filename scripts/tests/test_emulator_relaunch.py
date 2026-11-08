"""Tests of reboot_and_wait's recovery in scripts/adb_reboot.sh (M12-81): when the emulator process is gone after
`adb reboot`, the emulator is started again from the same AVD with the configured options, once, and the new boot is
waited for; without the options nothing is started. Run with a fake adb and a fake emulator on PATH.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HELPERS = os.path.join(HERE, "..", "adb_reboot.sh")

# The phone: boot id "old" until the fake emulator has been started (its marker file), then "new" and booted.
FAKE_ADB = """#!/bin/bash
echo "$*" >> "$FAKE_LOG"
case "$*" in
  reboot) exit 0;;
  get-state) if [ -f "$MARKER" ]; then echo device; else echo "error: device 'emulator-5554' not found" >&2; exit 1; fi;;
  wait-for-device) [ -f "$MARKER" ] && exit 0 || exit 1;;
  "shell cat /proc/sys/kernel/random/boot_id") if [ -f "$MARKER" ]; then echo new-boot; else echo old-boot; fi;;
  "shell getprop sys.boot_completed") [ -f "$MARKER" ] && echo 1;;
  *) exit 0;;
esac
"""

FAKE_EMULATOR = """#!/bin/bash
echo "$*" > "$MARKER"
sleep 30
"""


class EmulatorRelaunch(unittest.TestCase):
    def run_reboot(self, options: str, seconds: int = 25) -> tuple[int, str, list[str], str | None]:
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "sdk", "emulator"))
            for path, text in ((os.path.join(tmp, "adb"), FAKE_ADB), (os.path.join(tmp, "sdk", "emulator", "emulator"), FAKE_EMULATOR)):
                with open(path, "w") as f:
                    f.write(text)
                os.chmod(path, 0o755)
            calls, marker, log = os.path.join(tmp, "calls.txt"), os.path.join(tmp, "started.txt"), os.path.join(tmp, "relaunch.log")
            open(calls, "w").close()
            env = {
                **os.environ,
                "PATH": tmp + os.pathsep + os.environ["PATH"],
                "FAKE_LOG": calls,
                "MARKER": marker,
                "ANDROID_HOME": os.path.join(tmp, "sdk"),
                "PF_EMULATOR_RELAUNCH_OPTIONS": options,
                "PF_EMULATOR_RELAUNCH_LOG": log,
            }
            script = (
                f'. "{HELPERS}"; emulator_running() {{ [ -f "$MARKER" ]; }}; '
                f'reboot_and_wait {seconds}; code=$?; echo "relaunched: ${{REBOOT_RELAUNCHED:-no}}"; echo "seen: $REBOOT_SEEN"; exit $code'
            )
            done = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=90, check=False)
            with open(calls) as f:
                made = [line.strip() for line in f if line.strip()]
            started = None
            if os.path.exists(marker):
                with open(marker) as f:
                    started = f.read().strip()
            subprocess.run(["pkill", "-f", os.path.join(tmp, "sdk", "emulator", "emulator")], check=False)
        return done.returncode, done.stdout, made, started

    def test_a_gone_emulator_is_started_again_and_the_new_boot_waited_for(self) -> None:
        code, out, calls, started = self.run_reboot("-no-window -tcpdump /tmp/x.pcap")
        self.assertEqual(code, 0, out)
        self.assertEqual(started, "-port 5554 -avd test -no-window -tcpdump /tmp/x.pcap")
        self.assertIn("relaunched: the emulator process had exited at the restart (M10-54), so it was started again from the same AVD", out)
        self.assertEqual(calls[0], "shell cat /proc/sys/kernel/random/boot_id")
        self.assertEqual(calls[1], "reboot")

    def test_without_the_options_nothing_is_started(self) -> None:
        code, out, _, started = self.run_reboot("", seconds=12)
        self.assertEqual(code, 1)
        self.assertIsNone(started)
        self.assertIn("relaunched: no", out)
        self.assertIn("emulator process: none on this host", out)


if __name__ == "__main__":
    unittest.main()
