#!/usr/bin/env python3
"""Draws the pictures of the Musik package (docs/app-entwicklung/09 §2), like tools/app_symbole.py.

- icon.png (64 x 64): the launcher icon, two notes joined by a beam, with two arcs of sound beside them.
- note.png (96 x 96): the player page's picture while a song has no cover: the notes in a cover's frame.
- radio.png (96 x 96): the station page's picture: a radio with its loudspeaker, dial and aerial.

Calm grey line art for the glasses: 8-bit grey on black (black is see-through on the lens), strokes of 2-3 px
at levels 9-13 of the 16 the glasses show, no large bright areas. Drawn at 8x size and averaged down, then
rounded to the 16 levels (n x 16, level 15 = 255).

    python3 packages/musik/symbol.py     # writes src/main/assets/apps/ch.madtreasures.musik/*.png

Needs Pillow.
"""

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SCALE = 8
OUT = Path(__file__).resolve().parent / "src/main/assets/apps/ch.madtreasures.musik"


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


def arc(d, cx, cy, r, start, end, width, value):
    """An arc around (cx, cy) from [start] to [end] degrees (0 = right, clockwise), with round ends."""
    steps = max(8, int(abs(end - start) / 4))
    pts = [(cx + r * math.cos(math.radians(start + (end - start) * i / steps)), cy + r * math.sin(math.radians(start + (end - start) * i / steps))) for i in range(steps + 1)]
    line(d, pts, width, value)


def ring(d, cx, cy, r, width, value):
    d.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), outline=value, width=s(width))


def notes(d, x, y, k, value):
    """Two eighth notes joined by a beam; (x, y) is the left head's centre, [k] the size (1 = icon)."""
    left = (x, y)
    right = (x + 18 * k, y - 4 * k)
    stem = 2.6 * k
    for hx, hy in (left, right):
        d.ellipse((s(hx - 5.2 * k), s(hy - 3.8 * k), s(hx + 5.2 * k), s(hy + 3.8 * k)), fill=value)
    # Stems up from the right edge of each head, joined by a thick beam.
    lx = left[0] + 4.4 * k
    rx = right[0] + 4.4 * k
    top_l = left[1] - 26 * k
    top_r = right[1] - 26 * k
    line(d, [(lx, left[1] - 1 * k), (lx, top_l)], stem, value)
    line(d, [(rx, right[1] - 1 * k), (rx, top_r)], stem, value)
    line(d, [(lx, top_l), (rx, top_r)], 5 * k, value)


def finish(img, size):
    small = img.resize((size, size), Image.BOX)
    return small.point(lambda v: level(min(15, (v + 8) >> 4)))


def icon():
    size = 64
    img = Image.new("L", (s(size), s(size)), 0)
    d = ImageDraw.Draw(img)
    notes(d, 15, 47, 1.0, level(13))
    # Sound going out to the right, dimmer the further it goes.
    arc(d, 40, 30, 10, -45, 45, 2.5, level(11))
    arc(d, 40, 30, 17, -45, 45, 2.5, level(9))
    return finish(img, size)


def note_picture():
    size = 96
    img = Image.new("L", (s(size), s(size)), 0)
    d = ImageDraw.Draw(img)
    # A cover's frame, dim, so the notes stand out.
    d.rounded_rectangle((s(6), s(6), s(90), s(90)), radius=s(10), outline=level(7), width=s(2))
    notes(d, 31, 64, 1.45, level(12))
    return finish(img, size)


def radio_picture():
    size = 96
    img = Image.new("L", (s(size), s(size)), 0)
    d = ImageDraw.Draw(img)
    # Aerial, slanting up from the top right of the body.
    line(d, [(66, 30), (82, 8)], 2.5, level(10))
    d.ellipse((s(82 - 3), s(8 - 3), s(82 + 3), s(8 + 3)), fill=level(10))
    # Body.
    d.rounded_rectangle((s(8), s(30), s(88), s(84)), radius=s(9), outline=level(12), width=s(3))
    # Loudspeaker: rings around a dot.
    ring(d, 32, 57, 15, 2.5, level(11))
    ring(d, 32, 57, 8, 2, level(9))
    d.ellipse((s(32 - 2.5), s(57 - 2.5), s(32 + 2.5), s(57 + 2.5)), fill=level(11))
    # Dial: a scale with a needle, and two knobs below it.
    d.rounded_rectangle((s(54), s(40), s(80), s(56)), radius=s(3), outline=level(10), width=s(2))
    for i in range(5):
        x = 58 + i * 4.5
        line(d, [(x, 44), (x, 47 if i % 2 else 49)], 1.2, level(9))
    line(d, [(70, 42), (70, 54)], 2, level(13))
    ring(d, 60, 68, 4.5, 2, level(10))
    ring(d, 74, 68, 4.5, 2, level(10))
    return finish(img, size)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, picture in (("icon.png", icon), ("note.png", note_picture), ("radio.png", radio_picture)):
        path = OUT / name
        picture().save(path, optimize=True)
        print(path, path.stat().st_size, "Bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
