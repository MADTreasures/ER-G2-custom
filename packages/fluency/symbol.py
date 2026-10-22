#!/usr/bin/env python3
"""Draws the launcher icon of the Fluency package (docs/app-entwicklung/09 §2), like tools/app_symbole.py.

Two speech bubbles, one in front of the other: what is said and its translation. Calm grey line art for
the glasses: 64 x 64, 8-bit grey on black (black is see-through on the lens), strokes of 2-3 px at levels
9-13 of the 16 the glasses show, no large bright areas. Drawn at 8x size and averaged down, then rounded to
the 16 levels (n x 16, level 15 = 255).

    python3 packages/fluency/symbol.py     # writes src/main/assets/apps/ch.madtreasures.fluency/icon.png

Needs Pillow.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 64
SCALE = 8
OUT = Path(__file__).resolve().parent / "src/main/assets/apps/ch.madtreasures.fluency/icon.png"


def level(n):
    """Grey value of level n, as the glasses round it back exactly."""
    return 255 if n >= 15 else n * 16


def s(v):
    """Icon pixels to canvas pixels."""
    return round(v * SCALE)


def bubble(d, box, radius, tail, width, value):
    """A rounded box with a pointed tail below it; [tail] is (base left x, tip x, tip y, base right x)."""
    x0, y0, x1, y1 = box
    left, tip_x, tip_y, right = tail
    # Inside black first, so a bubble covers what lies behind it.
    d.rounded_rectangle((s(x0), s(y0), s(x1), s(y1)), radius=s(radius), fill=0)
    d.polygon([(s(left), s(y1) - 1), (s(tip_x), s(tip_y)), (s(right), s(y1) - 1)], fill=0)
    d.rounded_rectangle((s(x0), s(y0), s(x1), s(y1)), radius=s(radius), outline=value, width=s(width))
    # Open the outline where the tail leaves it, then draw the tail's two edges with round ends.
    half = s(width) / 2
    d.rectangle((s(left) + half, s(y1) - s(width), s(right) - half, s(y1)), fill=0)
    line(d, [(left, y1 - width / 2), (tip_x, tip_y), (right, y1 - width / 2)], width, value)


def line(d, points, width, value):
    pts = [(s(x), s(y)) for x, y in points]
    d.line(pts, fill=value, width=s(width), joint="curve")
    r = s(width) / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=value)


def fluency():
    big = SIZE * SCALE
    img = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(img)
    # Behind, up right: what is said, dimmer, with two lines of text.
    bubble(d, (24, 7, 58, 33), 8, (47, 52, 41, 54), 2.5, level(9))
    line(d, [(31, 15), (51, 15)], 2.5, level(9))
    line(d, [(31, 22), (44, 22)], 2.5, level(9))
    # In front, down left: the translation, brighter, with its two lines.
    bubble(d, (6, 23, 44, 51), 9, (13, 11, 59, 22), 3, level(13))
    line(d, [(14, 32), (36, 32)], 3, level(12))
    line(d, [(14, 41), (29, 41)], 3, level(12))
    small = img.resize((SIZE, SIZE), Image.BOX)
    return small.point(lambda v: level(min(15, (v + 8) >> 4)))


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fluency().save(OUT, optimize=True)
    print(OUT, OUT.stat().st_size, "Bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
