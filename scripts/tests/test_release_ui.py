"""Tests of scripts/release/ui.py's reading of a screen (M11's signed-bundle smoke): a node found by text or description,
the centre of its bounds, a Compose text field found from its label (the label is a node inside the field's bounds), and
the field itself when the label is the field's own text.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
import ui  # noqa: E402

SCREEN = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" text="" content-desc="" bounds="[0,0][1080,2400]">
    <node class="android.widget.TextView" text="Add proxy" content-desc="" bounds="[64,200][400,280]"/>
    <node class="android.widget.EditText" text="" content-desc="" bounds="[64,400][1016,560]">
      <node class="android.widget.TextView" text="Server" content-desc="" bounds="[96,420][300,470]"/>
    </node>
    <node class="android.widget.EditText" text="Port" content-desc="" bounds="[64,600][1016,760]"/>
    <node class="android.widget.Button" text="" content-desc="Save" bounds="[64,2200][1016,2320]"/>
  </node>
</hierarchy>
"""


TREE = ui.tree


class ScreenReading(unittest.TestCase):
    def setUp(self) -> None:
        self.root = ET.fromstring(SCREEN)
        ui.tree = lambda: self.root  # type: ignore[assignment]

    def tearDown(self) -> None:
        ui.tree = TREE

    def test_found_by_text_or_description(self) -> None:
        self.assertEqual(ui.centre(ui.find(self.root, "Add proxy")), (232, 240))
        self.assertEqual(ui.centre(ui.find(self.root, "Save")), (540, 2260))
        self.assertIsNone(ui.find(self.root, "Connect"))
        self.assertIsNotNone(ui.find(self.root, "proxy", contains=True))

    def test_field_from_its_label_inside_the_field(self) -> None:
        node = ui.field("Server")
        self.assertEqual(node.get("class"), "android.widget.EditText")
        self.assertEqual(ui.centre(node), (540, 480))

    def test_field_whose_own_text_is_the_label(self) -> None:
        self.assertEqual(ui.centre(ui.field("Port")), (540, 680))

    def test_texts_lists_what_the_screen_shows_but_not_what_was_typed(self) -> None:
        # The Port field's own text (its label while empty, what was typed after) is a text field's and stays out.
        self.assertEqual(ui.texts(), ["Add proxy", "Server", "Save"])
        typed = ET.fromstring(SCREEN.replace('text="Port"', 'text="hunter22"'))
        ui.tree = lambda: typed  # type: ignore[assignment]
        self.assertNotIn("hunter22", ui.texts())

    def test_a_failed_dump_is_never_the_previous_screen(self) -> None:
        calls: list[tuple[str, ...]] = []

        def fake_adb(*args: str, timeout: int = 60) -> str:
            calls.append(args)
            if args[1].startswith("uiautomator dump"):
                return "ERROR: could not get idle state."
            return SCREEN  # the old file, were it still there

        real_adb, real_sleep = ui.adb, ui.time.sleep
        ui.adb = fake_adb  # type: ignore[assignment]
        ui.time.sleep = lambda _s: None  # type: ignore[assignment]
        ui.tree = TREE
        try:
            self.assertIsNone(ui.tree())
        finally:
            ui.adb, ui.time.sleep = real_adb, real_sleep  # type: ignore[assignment]
        self.assertEqual([c for c in calls if c[1].startswith("rm -f")][:1], [("shell", f"rm -f {ui.DUMP}")])
        self.assertEqual(sum(1 for c in calls if c[1].startswith("uiautomator dump")), 3)
        self.assertFalse(any(c[0] == "exec-out" for c in calls))


class Typing(unittest.TestCase):
    """type_into reads the field back and types again when the field lost a character (M12-75)."""

    def setUp(self) -> None:
        self.field_text = ""
        self.typed: list[str] = []
        self.said: list[str] = []
        self.drop_first = True

        def fake_adb(*args: str, timeout: int = 60) -> str:
            if args[0] == "shell" and args[1].startswith("input text "):
                value = args[1][len("input text ") :].strip("'")
                if self.drop_first:
                    self.drop_first = False
                    value = value[1:]
                self.field_text += value
                self.typed.append(value)
            elif args[0] == "shell" and args[1].startswith("input keyevent KEYCODE_DEL"):
                self.field_text = self.field_text[: -args[1].count("KEYCODE_DEL")] if self.field_text else ""
            return ""

        self.real = ui.adb, ui.time.sleep, ui.tree, ui.say
        ui.adb = fake_adb  # type: ignore[assignment]
        ui.time.sleep = lambda _s: None  # type: ignore[assignment]
        ui.tree = lambda: ET.fromstring(SCREEN.replace('text="Port"', f'text="{self.field_text or "Port"}"'))  # type: ignore[assignment]
        ui.say = self.said.append  # type: ignore[assignment]

    def tearDown(self) -> None:
        ui.adb, ui.time.sleep, ui.tree, ui.say = self.real  # type: ignore[assignment]

    def test_a_lost_first_character_is_typed_again(self) -> None:
        ui.type_into("Port", 540, 680, "1080")
        self.assertEqual(self.field_text, "1080")
        self.assertEqual(self.typed, ["080", "1080"])
        self.assertIn("typing again", self.said[0])
        self.assertIn("try 2", self.said[1])

    def test_a_field_that_took_the_value_is_typed_once(self) -> None:
        self.drop_first = False
        ui.type_into("Port", 540, 680, "1080")
        self.assertEqual(self.typed, ["1080"])
        self.assertEqual(len(self.said), 1)

    def test_a_masked_password_counts_as_taken(self) -> None:
        self.drop_first = False
        ui.tree = lambda: ET.fromstring(SCREEN.replace('text="Port"', 'text="••••••••"'))  # type: ignore[assignment]
        ui.type_into("Port", 540, 680, "hunter22")
        self.assertEqual(self.typed, ["hunter22"])

    def test_a_field_that_never_takes_the_value_fails_the_step(self) -> None:
        ui.tree = lambda: ET.fromstring(SCREEN)  # type: ignore[assignment]
        with self.assertRaises(SystemExit):
            ui.type_into("Port", 540, 680, "1080")
        self.assertEqual(len(self.typed), 3)


if __name__ == "__main__":
    unittest.main()
