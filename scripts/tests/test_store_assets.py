"""Tests of store_assets.py's check against Play's specifications (on PNG headers written here, no Pillow needed) and
of the mark's path reading.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "release"))
import store_assets  # noqa: E402


def png(path: str, width: int, height: int, colour: int = 6, depth: int = 8) -> None:
    """A minimal PNG of that size (one black row repeated; enough for a header check)."""
    channels = {6: 4, 2: 3, 0: 1}[colour]
    raw = b"".join(b"\x00" + bytes(width * channels) for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, colour, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw)))
        f.write(chunk(b"IEND", b""))


class Check(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.out = self.dir.name
        os.makedirs(os.path.join(self.out, "screenshots"))
        png(os.path.join(self.out, "icon-512.png"), 512, 512, colour=6)
        png(os.path.join(self.out, "feature-graphic-1024x500.png"), 1024, 500, colour=2)
        shots = []
        for i, source in enumerate(("B4_home_connected_light", "D1_add_proxy_light"), start=1):
            name = f"{i}-{source}.png"
            png(os.path.join(self.out, "screenshots", name), 1080, 1920, colour=2)
            shots.append({"file": name, "source": f"app/screenshots/{source}.png", "caption": f"Caption {i}"})
        with open(os.path.join(self.out, "assets.json"), "w", encoding="utf-8") as f:
            json.dump({"screenshots": shots}, f)
        # The listing's table is the repository's; the test's folder stands in for it.
        self.listing = store_assets.listing_shots
        store_assets.listing_shots = lambda: [store_assets.Shot(s["source"].split("/")[-1][:-4], s["caption"]) for s in shots]

    def tearDown(self) -> None:
        store_assets.listing_shots = self.listing
        self.dir.cleanup()

    def run_check(self) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = store_assets.check(self.out)
        return code, out.getvalue()

    def test_assets_to_specification_pass(self) -> None:
        code, text = self.run_check()
        self.assertEqual(code, 0, text)
        self.assertIn("OK", text)

    def test_an_icon_without_alpha_or_of_another_size_fails(self) -> None:
        png(os.path.join(self.out, "icon-512.png"), 512, 512, colour=2)
        self.assertIn("RGB, not RGBA", self.run_check()[1])
        png(os.path.join(self.out, "icon-512.png"), 256, 256, colour=6)
        self.assertIn("256×256, not 512×512", self.run_check()[1])

    def test_a_feature_graphic_with_alpha_fails(self) -> None:
        png(os.path.join(self.out, "feature-graphic-1024x500.png"), 1024, 500, colour=6)
        self.assertIn("RGBA, not RGB", self.run_check()[1])

    def test_a_screenshot_past_two_to_one_or_not_nine_sixteen_fails(self) -> None:
        png(os.path.join(self.out, "screenshots", "1-B4_home_connected_light.png"), 1078, 2399, colour=2)
        text = self.run_check()[1]
        self.assertIn("the longer side over twice the shorter", text)
        self.assertIn("not 9:16", text)

    def test_the_folder_must_match_the_listing(self) -> None:
        png(os.path.join(self.out, "screenshots", "3-extra.png"), 1080, 1920, colour=2)
        self.assertIn("assets.json lists other screenshots", self.run_check()[1])

    def test_a_changed_caption_in_the_listing_fails_until_remade(self) -> None:
        store_assets.listing_shots = lambda: [store_assets.Shot("B4_home_connected_light", "Caption 1"), store_assets.Shot("D1_add_proxy_light", "Changed")]
        self.assertIn("not docs/m11/store-listing.md §4's", self.run_check()[1])

    def test_a_non_png_fails(self) -> None:
        with open(os.path.join(self.out, "icon-512.png"), "wb") as f:
            f.write(b"GIF89a")
        self.assertIn("not a PNG", self.run_check()[1])


class Mark(unittest.TestCase):
    def test_the_shield_path_closes_where_it_starts(self) -> None:
        lines = store_assets.path_points(store_assets.SHIELD)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0][0], (12.0, 3.0))
        self.assertEqual(lines[0][-1], (12.0, 3.0))
        xs = [p[0] for p in lines[0]]
        ys = [p[1] for p in lines[0]]
        self.assertAlmostEqual(min(xs), 5.0)
        self.assertAlmostEqual(max(xs), 19.0)
        self.assertAlmostEqual(max(ys), 21.0, places=1)

    def test_the_arrow_is_two_strokes(self) -> None:
        lines = store_assets.path_points(store_assets.ARROW)
        self.assertEqual([len(line) for line in lines], [2, 3])
        self.assertEqual(lines[0], [(8.5, 12.0), (15.5, 12.0)])


if __name__ == "__main__":
    unittest.main()
