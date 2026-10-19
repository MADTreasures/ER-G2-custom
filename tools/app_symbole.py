#!/usr/bin/env python3
"""Draws the launcher icons of the app packages (docs/app-entwicklung/09 §2).

Calm grey line art for the glasses: 64 x 64, 8-bit grey, black background (black is see-through on the
lens), strokes of 2-3 px at levels 10-14 of the 16 the glasses show, no large bright areas. Drawn at
8x size and averaged down, then rounded to the 16 levels (n x 16, level 15 = 255) so the edges stay soft.

    python3 tools/app_symbole.py            # writes the icons into packages/<name>/src/main/assets/...

Needs Pillow.
"""

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 64
SCALE = 8
ROOT = Path(__file__).resolve().parent.parent


def level(n):
    """Grey value of level n, as the glasses round it back exactly."""
    return 255 if n >= 15 else n * 16


def canvas():
    big = SIZE * SCALE
    return Image.new("L", (big, big), 0)


def finish(img):
    small = img.resize((SIZE, SIZE), Image.BOX)
    # Round every pixel to the nearest of the 16 levels.
    return small.point(lambda v: level(min(15, (v + 8) >> 4)))


def s(v):
    """Icon pixels to canvas pixels."""
    return v * SCALE


def rounded_rect(d, box, radius, width, value):
    x0, y0, x1, y1 = box
    d.rounded_rectangle((s(x0), s(y0), s(x1), s(y1)), radius=s(radius), outline=value, width=round(s(width)))


def polyline(d, points, width, value, closed=False):
    pts = [(s(x), s(y)) for x, y in points]
    if closed:
        pts.append(pts[0])
    d.line(pts, fill=value, width=round(s(width)), joint="curve")
    # Round ends and joins.
    r = s(width) / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=value)


def youtube():
    """A play triangle in a rounded rectangle."""
    img = canvas()
    d = ImageDraw.Draw(img)
    rounded_rect(d, (5, 13, 59, 51), radius=12, width=3, value=level(12))
    # The triangle, optically centred (its centroid sits right of the middle of its bounding box).
    polyline(d, [(27, 23), (27, 41), (42, 32)], width=3, value=level(14), closed=True)
    return finish(img)


def browser():
    """A globe: the outline, the equator, two parallels and two meridians."""
    img = canvas()
    d = ImageDraw.Draw(img)
    cx, cy, r = 32, 32, 26
    inner = level(10)
    # Meridian: an ellipse a little narrower than half the globe, and the axis.
    mw = r * 0.48
    d.ellipse((s(cx - mw), s(cy - r), s(cx + mw), s(cy + r)), outline=inner, width=round(s(2)))
    d.line((s(cx), s(cy - r), s(cx), s(cy + r)), fill=inner, width=round(s(2)))
    # Equator and two parallels, each as wide as the globe is at that height.
    for dy in (-r * 0.5, 0, r * 0.5):
        half = math.sqrt(r * r - dy * dy)
        d.line((s(cx - half), s(cy + dy), s(cx + half), s(cy + dy)), fill=inner, width=round(s(2)))
    d.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), outline=level(13), width=round(s(3)))
    return finish(img)


ICONS = {
    "packages/youtube/src/main/assets/apps/ch.madtreasures.youtube/icon.png": youtube,
    "packages/browser/src/main/assets/apps/ch.madtreasures.browser/icon.png": browser,
}


def main():
    for rel, draw in ICONS.items():
        out = ROOT / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        draw().save(out, optimize=True)
        print(out.relative_to(ROOT), out.stat().st_size, "Bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
