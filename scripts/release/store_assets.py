#!/usr/bin/env python3
"""The Google Play store assets (M11), made from what the app already has and checked against Play's specifications.

    scripts/release/store_assets.py make [--out store]     the icon, the feature graphic and the screenshots
    scripts/release/store_assets.py check [--out store]    every file there has the size, format and depth Play asks for

The icon is the app's own adaptive icon (app/src/main/res/drawable/ic_launcher_foreground.xml: the shield-and-arrow mark
on the 108 dp canvas, drawn here from the same path geometry) on its brand-teal background, at Play's 512×512, 32-bit
PNG. The feature graphic (1024×500, no alpha) is the mark, the name and the one-line description. The screenshots are
the app's own committed screenshots (app/screenshots, the Roborazzi ones, 1078×2399, the app's window alone without a
status bar, which Play would refuse as they are: their sides' ratio is 2.23, past 2:1) reframed to 9:16 at 1080×1920 —
each on a plain background under a caption — in the order the listing shows them (docs/m11/store-listing.md §4), with the captions
checked by scripts/check_listing_claims.py like the rest of the listing's text.

Play's specifications as read on 2026-11-07 (docs/m11/release-checklist.md §2 has the sources): app icon 512×512,
32-bit PNG with alpha, up to 1024 KB; feature graphic 1024×500, JPEG or 24-bit PNG without alpha; phone screenshots
two to eight, PNG or JPEG, each side 320 to 3840 px, the longer side at most twice the shorter, 16:9 or 9:16 for
Play's recommendation placements.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from dataclasses import dataclass

import struct

try:  # `make` draws with Pillow; `check` reads PNG headers alone, so CI needs nothing installed.
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    Image = ImageDraw = ImageFont = None  # type: ignore[assignment]

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
TEAL = (0x20, 0xB8, 0xA5)
NAVY = (0x14, 0x24, 0x3A)
BACKGROUND_LIGHT = (0xF6, 0xF8, 0xFB)
WHITE = (255, 255, 255)

# The mark's geometry, as ic_launcher_foreground.xml draws it: two stroked paths in a 24-unit space (stroke 2.2, round
# caps and joins), scaled 2.5× and placed at (24, 24) on the 108 dp canvas.
SHIELD = "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"
ARROW = "M8.5 12h7M13 9.5l2.5 2.5 -2.5 2.5"
STROKE = 2.2


def bezier(p0, p1, p2, p3, steps=24):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append(
            (
                u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
            )
        )
    return out


def path_points(path: str) -> list[list[tuple[float, float]]]:
    """The polylines of an SVG path of the commands the mark uses (M, l, v, V, h, c, z), curves sampled."""
    import re

    tokens = re.findall(r"[MlvVhcz]|-?\d*\.?\d+", path)
    lines: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    x = y = 0.0
    start = (0.0, 0.0)
    i = 0
    command = ""
    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
            if command == "z":
                current.append(start)
                continue
        nums = lambda n: [float(tokens[i + k]) for k in range(n)]  # noqa: E731
        if command == "M":
            x, y = nums(2)
            i += 2
            if current:
                lines.append(current)
            current = [(x, y)]
            start = (x, y)
            command = "l"
        elif command == "l":
            dx, dy = nums(2)
            i += 2
            x, y = x + dx, y + dy
            current.append((x, y))
        elif command == "h":
            (dx,) = nums(1)
            i += 1
            x += dx
            current.append((x, y))
        elif command == "v":
            (dy,) = nums(1)
            i += 1
            y += dy
            current.append((x, y))
        elif command == "V":
            (y,) = nums(1)
            i += 1
            current.append((x, y))
        elif command == "c":
            c = nums(6)
            i += 6
            p1 = (x + c[0], y + c[1])
            p2 = (x + c[2], y + c[3])
            p3 = (x + c[4], y + c[5])
            current.extend(bezier((x, y), p1, p2, p3))
            x, y = p3
        else:
            raise ValueError(f"unsupported path command {command!r}")
    if current:
        lines.append(current)
    return lines


def draw_mark(draw: ImageDraw.ImageDraw, origin: tuple[float, float], unit: float, colour) -> None:
    """The mark with its 24-unit space mapped to `unit` pixels per unit from `origin`."""
    width = max(1, round(STROKE * unit))
    r = width / 2
    for path in (SHIELD, ARROW):
        for line in path_points(path):
            points = [(origin[0] + px * unit, origin[1] + py * unit) for px, py in line]
            # Each segment on its own, a disc at every vertex: round caps and round joins without Pillow's joint artefacts.
            for a, b in zip(points, points[1:]):
                draw.line([a, b], fill=colour, width=width)
            for px, py in points:
                draw.ellipse((px - r, py - r, px + r, py + r), fill=colour)


def supersampled(size: tuple[int, int], paint, factor: int = 4, mode: str = "RGBA") -> Image.Image:
    big = Image.new(mode, (size[0] * factor, size[1] * factor))
    paint(ImageDraw.Draw(big), factor)
    return big.resize(size, Image.LANCZOS)


def icon() -> Image.Image:
    """The adaptive icon as a launcher shows it: the inner 72 dp of the 108 dp canvas (what every launcher mask keeps)
    at 512 px, so the mark is the same size on Play as on the phone (review C-9); Play rounds the corners itself."""
    def paint(draw, f):
        draw.rectangle((0, 0, 512 * f, 512 * f), fill=TEAL + (255,))
        px_per_dp = 512 * f / 72
        unit = px_per_dp * 2.5
        origin = ((24 - 18) * px_per_dp, (24 - 18) * px_per_dp)
        draw_mark(draw, origin, unit, NAVY + (255,))

    return supersampled((512, 512), paint)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        f"/usr/share/fonts/opentype/inter/Inter{'Display' if size > 40 else ''}-{'SemiBold' if bold else 'Regular'}.otf",
        f"/usr/share/fonts/opentype/inter/Inter-{'SemiBold' if bold else 'Regular'}.otf",
        f"/usr/share/fonts/truetype/dejavu/DejaVuSans{'-Bold' if bold else ''}.ttf",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def wrap(draw: ImageDraw.ImageDraw, text: str, font_: ImageFont.FreeTypeFont | ImageFont.ImageFont, width: float) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in text.split():
        trial = f"{current} {word}".strip()
        if draw.textlength(trial, font=font_) > width and current:
            lines.append(current)
            current = word
        else:
            current = trial
    lines.append(current)
    return lines


def feature_graphic(title: str, line: str) -> Image.Image:
    def paint(draw, f):
        draw.rectangle((0, 0, 1024 * f, 500 * f), fill=TEAL)
        unit = 9.5 * f
        draw_mark(draw, (96 * f, 136 * f), unit, NAVY)
        draw.text((392 * f, 200 * f), title, font=font(92 * f, bold=True), fill=NAVY, anchor="ls")
        body = font(34 * f)
        y = 262 * f
        for text in wrap(draw, line, body, 1024 * f - 392 * f - 48 * f):
            draw.text((392 * f, y), text, font=body, fill=NAVY, anchor="ls")
            y += 44 * f

    return supersampled((1024, 500), paint, factor=2, mode="RGB")


@dataclass
class Shot:
    source: str  # the committed screenshot's stem (app/screenshots/<stem>.png)
    caption: str


def screenshot(shot: Shot, index: int) -> Image.Image:
    # The Roborazzi screenshots are the app's own window alone: no status bar, no navigation bar (Play's rule on
    # screenshots — no notification-bar content — is met by the source).
    source = Image.open(os.path.join(ROOT, "app", "screenshots", f"{shot.source}.png")).convert("RGB")
    canvas = Image.new("RGB", (1080, 1920), BACKGROUND_LIGHT if "_light" in shot.source else (0x10, 0x18, 0x20))
    draw = ImageDraw.Draw(canvas)
    caption_colour = NAVY if "_light" in shot.source else WHITE
    # The caption, wrapped to the width, in the top band.
    caption_font = font(54, bold=True)
    lines = wrap(draw, shot.caption, caption_font, 940)
    y = 120
    for text in lines:
        draw.text((540, y), text, font=caption_font, fill=caption_colour, anchor="mt")
        y += 70
    # The phone's screen under it, scaled to the room left, with rounded corners and a soft edge.
    top = y + 60
    height = 1920 - top - 80
    width = round(source.width * height / source.height)
    if width > 900:
        width = 900
        height = round(source.height * width / source.width)
    screen = source.resize((width, height), Image.LANCZOS)
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius=48, fill=255)
    x = (1080 - width) // 2
    edge = Image.new("RGB", (width + 8, height + 8), (0xD5, 0xDB, 0xE3) if "_light" in shot.source else (0x2A, 0x36, 0x44))
    edge_mask = Image.new("L", edge.size, 0)
    ImageDraw.Draw(edge_mask).rounded_rectangle((0, 0, edge.width - 1, edge.height - 1), radius=52, fill=255)
    canvas.paste(edge, (x - 4, top - 4), edge_mask)
    canvas.paste(screen, (x, top), mask)
    return canvas


def sha256(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def source_path(shot: Shot) -> str:
    return os.path.join(ROOT, "app", "screenshots", f"{shot.source}.png")


def listing_hash() -> str:
    """The title and short description the feature graphic was made from."""
    title, short = listing_lines()
    return hashlib.sha256(f"{title}\n{short}".encode()).hexdigest()


def listing_shots() -> list[Shot]:
    """The screenshots and captions from docs/m11/store-listing.md §4: a table of `| N | source | caption |` rows."""
    path = os.path.join(ROOT, "docs", "m11", "store-listing.md")
    shots: list[Shot] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) == 3 and cells[0].isdigit() and cells[1].startswith("`"):
                shots.append(Shot(cells[1].strip("`"), cells[2]))
    if not 2 <= len(shots) <= 8:
        sys.exit(f"docs/m11/store-listing.md §4 lists {len(shots)} screenshots; Play takes two to eight")
    return shots


def listing_lines() -> tuple[str, str]:
    """The app's name and short description from the listing (the fenced blocks `listing-title` and `listing-short`)."""
    path = os.path.join(ROOT, "docs", "m11", "store-listing.md")
    blocks: dict[str, str] = {}
    name = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("```listing-"):
                name = line.strip()[3:]
                blocks[name] = ""
            elif line.startswith("```"):
                name = None
            elif name:
                blocks[name] += line
    return blocks["listing-title"].strip(), blocks["listing-short"].strip()


def make(out: str) -> None:
    os.makedirs(os.path.join(out, "screenshots"), exist_ok=True)
    icon().save(os.path.join(out, "icon-512.png"), optimize=True)
    title, short = listing_lines()
    feature_graphic(title, short).save(os.path.join(out, "feature-graphic-1024x500.png"), optimize=True)
    facts = {"icon": "icon-512.png", "feature_graphic": "feature-graphic-1024x500.png", "listing": listing_hash(), "screenshots": []}
    for index, shot in enumerate(listing_shots(), start=1):
        name = f"{index}-{shot.source}.png"
        screenshot(shot, index).save(os.path.join(out, "screenshots", name), optimize=True)
        facts["screenshots"].append(
            {"file": name, "source": f"app/screenshots/{shot.source}.png", "source_sha256": sha256(source_path(shot)), "caption": shot.caption}
        )
    with open(os.path.join(out, "assets.json"), "w", encoding="utf-8") as f:
        json.dump(facts, f, indent=2)
        f.write("\n")
    print(f"made {out}/icon-512.png, {out}/feature-graphic-1024x500.png and {len(facts['screenshots'])} screenshots")


def png_header(path: str) -> tuple[int, int, str]:
    """(width, height, 'RGBA' | 'RGB' | other) from a PNG's IHDR, or a ValueError for a file that is not a PNG."""
    with open(path, "rb") as f:
        head = f.read(33)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    width, height, depth, colour = struct.unpack(">IIBB", head[16:26])
    mode = {(8, 6): "RGBA", (8, 2): "RGB"}.get((depth, colour), f"depth {depth} colour type {colour}")
    return width, height, mode


def check(out: str) -> int:
    problems: list[str] = []

    def expect(path: str, size: tuple[int, int], mode: str, max_bytes: int | None = None) -> None:
        full = os.path.join(out, path)
        if not os.path.isfile(full):
            problems.append(f"{path}: missing")
            return
        try:
            w, h, found = png_header(full)
        except ValueError:
            problems.append(f"{path}: not a PNG")
            return
        if (w, h) != size:
            problems.append(f"{path}: {w}×{h}, not {size[0]}×{size[1]}")
        if found != mode:
            problems.append(f"{path}: {found}, not {mode}")
        if max_bytes is not None and os.path.getsize(full) > max_bytes:
            problems.append(f"{path}: {os.path.getsize(full)} bytes, over {max_bytes}")

    expect("icon-512.png", (512, 512), "RGBA", 1024 * 1024)
    expect("feature-graphic-1024x500.png", (1024, 500), "RGB")
    folder = os.path.join(out, "screenshots")
    shots = sorted(f for f in os.listdir(folder) if f.endswith(".png")) if os.path.isdir(folder) else []
    if not 2 <= len(shots) <= 8:
        problems.append(f"screenshots: {len(shots)}, not two to eight")
    for name in shots:
        try:
            w, h, _ = png_header(os.path.join(folder, name))
        except ValueError:
            problems.append(f"screenshots/{name}: not a PNG")
            continue
        if not (320 <= w <= 3840 and 320 <= h <= 3840):
            problems.append(f"screenshots/{name}: {w}×{h}, a side outside 320–3840")
        if max(w, h) > 2 * min(w, h):
            problems.append(f"screenshots/{name}: {w}×{h}, the longer side over twice the shorter")
        if (w, h) != (1080, 1920):
            problems.append(f"screenshots/{name}: {w}×{h}, not 9:16 at 1080×1920 (Play's recommendation placements)")
    facts_path = os.path.join(out, "assets.json")
    if os.path.isfile(facts_path):
        with open(facts_path, encoding="utf-8") as f:
            facts = json.load(f)
        if [s["file"] for s in facts["screenshots"]] != shots:
            problems.append("assets.json lists other screenshots than the folder holds")
        if os.path.isfile(os.path.join(ROOT, "docs", "m11", "store-listing.md")):
            listed = [(f"{i}-{s.source}.png", s.caption) for i, s in enumerate(listing_shots(), start=1)]
            if [(s["file"], s["caption"]) for s in facts["screenshots"]] != listed:
                problems.append("assets.json's screenshots or captions are not docs/m11/store-listing.md §4's: run `store_assets.py make`")
            # A source screenshot that changed since the store one was made (a Roborazzi screenshot re-recorded), or a
            # changed title or short description: the store asset is stale (review C-8).
            for entry in facts["screenshots"]:
                source = os.path.join(ROOT, entry["source"])
                if not os.path.isfile(source):
                    problems.append(f"{entry['file']}: its source {entry['source']} is gone")
                elif sha256(source) != entry.get("source_sha256"):
                    problems.append(f"{entry['file']}: its source {entry['source']} changed since it was made: run `store_assets.py make`")
            if facts.get("listing") != listing_hash():
                problems.append("the feature graphic's title or short description changed since it was made: run `store_assets.py make`")
    else:
        problems.append("assets.json: missing")
    for problem in problems:
        print(f"PROBLEM {problem}")
    if problems:
        print(f"::error title=Store assets::{'; '.join(problems)}")
        return 1
    print(f"OK: icon 512×512 RGBA, feature graphic 1024×500 RGB, {len(shots)} screenshots 1080×1920, as docs/m11/store-listing.md lists them")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=["make", "check"])
    parser.add_argument("--out", default=os.path.join(ROOT, "store"))
    args = parser.parse_args(argv)
    if args.command == "make":
        make(args.out)
        return 0
    return check(args.out)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
