#!/usr/bin/env python3
"""Draws the launcher icon of the Navi package (docs/app-entwicklung/09 §2), like tools/app_symbole.py.

The arrow of a navigation, pointing up and to the right, with the route it came along dashed behind it.
Calm grey line art for the glasses: 64 x 64, 8-bit grey on black (black is see-through on the lens), strokes
of 2-3 px at levels 9-13 of the 16 the glasses show, no large bright areas. Drawn at 8x size and averaged
down, then rounded to the 16 levels (n x 16, level 15 = 255).

    python3 packages/navi/symbol.py     # writes src/main/assets/apps/ch.madtreasures.navi/icon.png

Needs Pillow.
"""

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 64
SCALE = 8
OUT = Path(__file__).resolve().parent / "src/main/assets/apps/ch.madtreasures.navi/icon.png"


def level(n):
    """Grey value of level n, as the glasses round it back exactly."""
    return 255 if n >= 15 else n * 16


def s(v):
    """Icon pixels to canvas pixels."""
    return round(v * SCALE)


def line(d, points, width, value):
    pts = [(s(x), s(y)) for x, y in points]
    d.line(pts, fill=value, width=s(width), joint="curve")
    r = s(width) / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=value)


def rotate(points, degrees, cx, cy):
    a = math.radians(degrees)
    return [(cx + (x - cx) * math.cos(a) - (y - cy) * math.sin(a), cy + (x - cx) * math.sin(a) + (y - cy) * math.cos(a)) for x, y in points]


def curve(p0, p1, p2, steps=40):
    """A quadratic curve from p0 over p1 to p2."""
    out = []
    for i in range(steps + 1):
        t = i / steps
        out.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]))
    return out


def dashed(d, points, dash, gap, width, value):
    """[points] as dashes of [dash] icon pixels with [gap] between them."""
    run = []
    at = 0.0
    on = True
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        pos = 0.0
        while pos < seg:
            left = (dash if on else gap) - at
            step = min(left, seg - pos)
            t0 = pos / seg
            t1 = (pos + step) / seg
            a = (x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0)
            b = (x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)
            if on:
                if not run:
                    run.append(a)
                run.append(b)
            pos += step
            at += step
            if at >= (dash if on else gap) - 1e-9:
                if on and run:
                    line(d, run, width, value)
                    run = []
                on = not on
                at = 0.0
    if on and len(run) > 1:
        line(d, run, width, value)


def navi():
    big = SIZE * SCALE
    img = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(img)
    # The way it came, dashed and dimmer, from the bottom left up to the arrow.
    dashed(d, curve((7, 57), (12, 34), (27, 36)), 6, 4.5, 2.5, level(9))
    # The arrow: a kite pointing up and to the right, with a notch at its back.
    kite = rotate([(32, 6), (49, 52), (32, 42), (15, 52)], 45, 32, 32)
    kite = [(x + 4, y - 4) for x, y in kite]
    line(d, kite + [kite[0]], 3, level(13))
    small = img.resize((SIZE, SIZE), Image.BOX)
    return small.point(lambda v: level(min(15, (v + 8) >> 4)))


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    navi().save(OUT, optimize=True)
    print(OUT, OUT.stat().st_size, "Bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
