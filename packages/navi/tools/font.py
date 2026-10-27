#!/usr/bin/env python3
"""Renders the fonts the Navi package draws into its map pictures (map labels, turn hints, distances).

Packages have no fonts of their own and cannot use Android's, so the glyphs come along as small files:
for every size one file `src/main/assets/apps/ch.madtreasures.navi/fonts/<name>.font`, rendered from Inter
(SIL Open Font License 1.1, see fonts/OFL.txt) with Pillow at the exact pixel size, with grey edges.

    python3 packages/navi/tools/font.py [INTER_DIR]      # default /usr/share/fonts/opentype/inter

File format "navi-font@1" (big-endian), read by Font.kt:
    8 bytes  "NAVIFONT"
    u8       format version (1)
    u8       pixel size
    i16      ascent (px above the baseline), i16 descent (px below), i16 line height
    u16      number of glyphs
    then a zlib stream with, per glyph: u16 code point, u16 advance in 1/16 px, u8 width, u8 height,
    i8 left (x of the bitmap from the pen), i8 top (y of its first row above the baseline), and after all
    glyphs their bitmaps, row by row, one byte of coverage (0–255) per pixel.
"""

import struct
import sys
import zlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "src/main/assets/apps/ch.madtreasures.navi/fonts"

# name: (file, pixel size)
FONTS = {
    "inter-15": ("Inter-Medium.otf", 15),
    "inter-20": ("Inter-SemiBold.otf", 20),
    "inter-26": ("Inter-SemiBold.otf", 26),
    "inter-46": ("Inter-Bold.otf", 46),
}

# ASCII, Latin-1, Latin Extended-A (place names of all of Europe), and the punctuation the app writes.
CHARS = (
    [chr(c) for c in range(0x20, 0x7F)]
    + [chr(c) for c in range(0xA0, 0x180)]
    + list("–—‘’‚“”„…•€→←↑↓")
)


def render(path, size):
    font = ImageFont.truetype(str(path), size)
    ascent, descent = font.getmetrics()
    glyphs = []
    for ch in CHARS:
        box = font.getbbox(ch, anchor="ls")
        advance = font.getlength(ch)
        w, h = max(0, box[2] - box[0]), max(0, box[3] - box[1])
        if w == 0 or h == 0:
            glyphs.append((ord(ch), advance, 0, 0, 0, 0, b""))
            continue
        image = Image.new("L", (w, h), 0)
        ImageDraw.Draw(image).text((-box[0], -box[1]), ch, font=font, fill=255, anchor="ls")
        glyphs.append((ord(ch), advance, w, h, box[0], -box[1], image.tobytes()))
    head = b"NAVIFONT" + struct.pack(">BBhhhH", 1, size, ascent, descent, ascent + descent, len(glyphs))
    meta = b"".join(struct.pack(">HHBBbb", c, round(a * 16), w, h, left, top) for c, a, w, h, left, top, _ in glyphs)
    bitmaps = b"".join(g[6] for g in glyphs)
    return head + zlib.compress(meta + bitmaps, 9)


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/usr/share/fonts/opentype/inter")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (file, size) in FONTS.items():
        data = render(source / file, size)
        (OUT / f"{name}.font").write_bytes(data)
        print(f"{name}.font: {len(data)} bytes")


if __name__ == "__main__":
    main()
