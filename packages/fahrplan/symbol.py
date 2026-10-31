#!/usr/bin/env python3
"""Draws the launcher icon of the Fahrplan package (docs/app-entwicklung/09 §2), like tools/app_symbole.py.

- icon.png (64 x 64): the front of a train on its rails, with windscreen and headlights.

Calm grey line art for the glasses: 8-bit grey on black (black is see-through on the lens), strokes of 2-3 px
at levels 9-13 of the 16 the glasses show, no large bright areas. Drawn at 8x size and averaged down, then
rounded to the 16 levels (n x 16, level 15 = 255).

    python3 packages/fahrplan/symbol.py     # writes src/main/assets/apps/ch.madtreasures.fahrplan/icon.png

Needs Pillow.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

SCALE = 8
OUT = Path(__file__).resolve().parent / "src/main/assets/apps/ch.madtreasures.fahrplan"


def level(n):
    """Grey value of level n, as the glasses round it back exactly."""
    return 255 if n >= 15 else n * 16


def s(v):
    """Picture pixels to canvas pixels."""
    return round(v * SCALE)


def line(d, points, width, value):
    pts = [(s(x), s(y)) for x, y in points]
    d.line(pts, fill=value, width=s(width), joint="curve")
    r = s(width) / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=value)


def dot(d, cx, cy, r, value):
    d.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), fill=value)


def finish(img, size):
    small = img.resize((size, size), Image.BOX)
    return small.point(lambda v: level(min(15, (v + 8) >> 4)))


def icon():
    size = 64
    img = Image.new("L", (s(size), s(size)), 0)
    d = ImageDraw.Draw(img)
    # The rails, dim, under everything.
    line(d, [(8, 58), (56, 58)], 2, level(9))
    # Feet of the front down to the rails, as on the station signs.
    line(d, [(23, 47), (18, 57)], 2.5, level(11))
    line(d, [(41, 47), (46, 57)], 2.5, level(11))
    # The front: body, windscreen, headlights.
    d.rounded_rectangle((s(16), s(5), s(48), s(48)), radius=s(9), outline=level(13), width=s(2.6))
    d.rounded_rectangle((s(21), s(11), s(43), s(27)), radius=s(3), outline=level(11), width=s(2))
    dot(d, 23.5, 38, 2.8, level(12))
    dot(d, 40.5, 38, 2.8, level(12))
    return finish(img, size)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / "icon.png"
    icon().save(path, optimize=True)
    print(path, path.stat().st_size, "Bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
