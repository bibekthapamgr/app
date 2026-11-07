#!/usr/bin/env python3
"""Drives the installed app's screens through adb for the signed-bundle smoke (M11), as a person would: it reads the
screen's accessibility tree (`uiautomator dump`), finds a node by the text or description it shows, and taps its
centre. The release build has no test hooks, so what it does here is what a user does.

    ui.py tap "Connect"            tap the node whose text or content description is exactly "Connect"
    ui.py tap-contains "Not now"   the first node whose text contains it
    ui.py wait "Connected" 60      wait up to 60 s for a node with that text (exit 1 if it never shows)
    ui.py type "Server" 10.0.2.2   tap the field labelled "Server", then type into it
    ui.py texts                    print every text on the screen (for the log when a step fails)
    ui.py screenshot out.png       the screen, for the evidence

Run: python3 scripts/release/ui.py <command> …   (adb on PATH; ANDROID_SERIAL as adb takes it)
"""

from __future__ import annotations

import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DUMP = "/sdcard/pf-ui.xml"


def adb(*args: str, timeout: int = 60) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout, check=False).stdout


def tree() -> ET.Element | None:
    """The screen's accessibility tree, or None when uiautomator could not give one in three tries (it waits for a quiet
    second, and a screen that redraws every second — Home's connection clock — can deny it that; review B-3). The old
    dump is removed first, so a failed dump never reads as the previous screen."""
    for _ in range(3):
        adb("shell", f"rm -f {DUMP}")
        out = adb("shell", f"uiautomator dump {DUMP}")
        if "dumped to" not in out:
            time.sleep(0.5)
            continue
        xml = adb("exec-out", f"cat {DUMP}")
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(0.5)
    return None


def nodes(root: ET.Element) -> list[ET.Element]:
    return list(root.iter("node"))


def label(node: ET.Element) -> str:
    return node.get("text") or node.get("content-desc") or ""


def find(root: ET.Element, text: str, contains: bool = False) -> ET.Element | None:
    for node in nodes(root):
        shown = label(node)
        if shown == text or (contains and text in shown):
            return node
    return None


def centre(node: ET.Element) -> tuple[int, int]:
    bounds = node.get("bounds", "[0,0][0,0]")
    left, top, right, bottom = (int(v) for v in bounds.replace("][", ",").strip("[]").split(","))
    return (left + right) // 2, (top + bottom) // 2


def bring_into_view() -> None:
    """A node below the fold is not in the tree (Compose drops clipped nodes): close the keyboard (Escape, which does not
    act as Back) and scroll up a little (review B-4)."""
    adb("shell", "input keyevent 111")
    time.sleep(0.4)
    adb("shell", "input swipe 540 1400 540 700 300")
    time.sleep(0.6)


def wait_for(text: str, seconds: float, contains: bool = False, scroll: bool = False) -> ET.Element | None:
    deadline = time.monotonic() + seconds
    tries = 0
    while True:
        root = tree()
        node = find(root, text, contains) if root is not None else None
        if node is not None or time.monotonic() >= deadline:
            return node
        tries += 1
        if scroll and tries % 3 == 0:
            bring_into_view()
        else:
            time.sleep(1)


def tap(text: str, contains: bool = False, seconds: float = 20) -> None:
    node = wait_for(text, seconds, contains, scroll=True)
    if node is None:
        fail(f"nothing on the screen shows {text!r}")
    x, y = centre(node)
    adb("shell", f"input tap {x} {y}")
    time.sleep(0.8)


def field(label_text: str) -> ET.Element:
    """The text field labelled so. A Compose text field's label is a text node of its own inside the field's bounds (or the
    field itself carries the label as its text while empty), so the field is the editable node that contains the label."""
    root = tree()
    if root is None:
        fail("no screen to read")
    labels = [n for n in nodes(root) if label(n) == label_text]
    if not labels:
        fail(f"no field labelled {label_text!r}")
    x, y = centre(labels[0])
    for node in nodes(root):
        if node.get("class") != "android.widget.EditText":
            continue
        left, top, right, bottom = (int(v) for v in node.get("bounds", "[0,0][0,0]").replace("][", ",").strip("[]").split(","))
        if left <= x <= right and top - 8 <= y <= bottom + 8:
            return node
    return labels[0]


def texts() -> list[str]:
    """What the screen shows, a text field's content left out (what was typed is not for the log)."""
    root = tree()
    if root is None:
        return []
    return [label(n) for n in nodes(root) if label(n) and n.get("class") != "android.widget.EditText"]


def fail(why: str) -> None:
    print(f"ui: {why}; the screen shows: {' | '.join(texts())[:600]}", file=sys.stderr)
    sys.exit(1)


def main(argv: list[str]) -> None:
    command, args = argv[0], argv[1:]
    if command == "tap":
        tap(args[0])
    elif command == "tap-contains":
        tap(args[0], contains=True)
    elif command == "wait":
        if wait_for(args[0], float(args[1]) if len(args) > 1 else 20) is None:
            fail(f"{args[0]!r} did not show")
    elif command == "type":
        if wait_for(args[0], 20, scroll=True) is None:
            fail(f"no field labelled {args[0]!r}")
        x, y = centre(field(args[0]))
        adb("shell", f"input tap {x} {y}")
        time.sleep(0.8)
        # input text takes no spaces as such; the smoke types addresses, ports and credentials (hex), which have none.
        adb("shell", f"input text '{args[1]}'")
        time.sleep(0.5)
    elif command == "texts":
        print("\n".join(texts()))
    elif command == "screenshot":
        with open(args[0], "wb") as f:
            f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60, check=False).stdout)
    else:
        fail(f"unknown command {command}")


if __name__ == "__main__":
    main(sys.argv[1:])
