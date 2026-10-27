#!/usr/bin/env python3
"""Cuts a small test map out of a mapsforge map file (binary format 3 to 5), without re-encoding anything.

    python3 cut_map.py IN.map OUT.map MIN_LAT MIN_LON MAX_LAT MAX_LON

Only the sub-file with the highest base zoom (zoom 12 to 21 on the mapsforge server's maps) goes into the
new file, and of it only the blocks that touch the box. The blocks are copied byte for byte with the
header's tag lists, so a reader meets the same encoding as in the real file. The new header names the
union of the copied blocks as its bounding box (pulled in by a hair, so that a reader computes the same
block range) and its own size, start position and sub-file table.
"""
import math
import struct
import sys


def vbeu(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return v, i


def string(b, i):
    n, i = vbeu(b, i)
    return b[i:i + n], i + n


def enc_vbeu(v):
    out = bytearray()
    while True:
        c = v & 0x7F
        v >>= 7
        if v:
            out.append(c | 0x80)
        else:
            out.append(c)
            return bytes(out)


def enc_string(raw):
    return enc_vbeu(len(raw)) + raw


def tile_x(lon, z):
    return min(max(int(math.floor((lon + 180) / 360 * (1 << z))), 0), (1 << z) - 1)


def tile_y(lat, z):
    s = math.sin(math.radians(lat))
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * (1 << z)
    return min(max(int(y), 0), (1 << z) - 1)


def x_lon(x, z):
    return x / (1 << z) * 360 - 180


def y_lat(y, z):
    n = math.pi - 2 * math.pi * y / (1 << z)
    return math.degrees(math.atan(math.sinh(n)))


def main(src, dst, min_lat, min_lon, max_lat, max_lon):
    data = open(src, 'rb').read()
    assert data[:20] == b'mapsforge binary OSM', 'no mapsforge map'
    version, = struct.unpack('>i', data[24:28])
    bbox = [v / 1e6 for v in struct.unpack('>iiii', data[44:60])]
    tile_size, = struct.unpack('>h', data[60:62])
    i = 62
    projection, i = string(data, i)
    flags = data[i]
    i += 1
    optional_start = i
    if flags & 0x40:
        i += 8
    if flags & 0x20:
        i += 1
    for bit in (0x10, 0x08, 0x04):
        if flags & bit:
            _, i = string(data, i)
    optional = data[optional_start:i]
    tags_start = i
    for _ in range(2):
        n, = struct.unpack('>h', data[i:i + 2])
        i += 2
        for _ in range(n):
            _, i = string(data, i)
    tag_lists = data[tags_start:i]
    count = data[i]
    i += 1
    subs = []
    for _ in range(count):
        base, lo, hi = data[i], data[i + 1], data[i + 2]
        start, size = struct.unpack('>qq', data[i + 3:i + 19])
        i += 19
        subs.append((base, lo, hi, start, size))
    base, lo, hi, start, size = max(subs)
    debug = bool(flags & 0x80)
    if debug:
        raise SystemExit('maps with debug signatures are not supported')
    left, right = tile_x(bbox[1], base), tile_x(bbox[3], base)
    top, bottom = tile_y(bbox[2], base), tile_y(bbox[0], base)
    width = right - left + 1
    blocks = width * (bottom - top + 1)

    def entry(n):
        raw = data[start + 5 * n:start + 5 * n + 5]
        v = int.from_bytes(raw, 'big')
        return v & 0x7FFFFFFFFF, bool(v & 0x8000000000)

    def block(x, y):
        n = (y - top) * width + (x - left)
        offset, water = entry(n)
        end = entry(n + 1)[0] if n + 1 < blocks else size
        return data[start + offset:start + end], water

    cl, cr = max(tile_x(min_lon, base), left), min(tile_x(max_lon, base), right)
    ct, cb = max(tile_y(max_lat, base), top), min(tile_y(min_lat, base), bottom)
    eps = 1e-6
    new_bbox = (y_lat(cb + 1, base) + eps, x_lon(cl, base) + eps, y_lat(ct, base) - eps, x_lon(cr + 1, base) - eps)
    micro = [int(round(v * 1e6)) for v in new_bbox]
    # The new range from the new box, exactly as a reader computes it.
    nl, nr = tile_x(micro[1] / 1e6, base), tile_x(micro[3] / 1e6, base)
    nt, nb = tile_y(micro[2] / 1e6, base), tile_y(micro[0] / 1e6, base)
    assert (nl, nr, nt, nb) == (cl, cr, ct, cb), ((nl, nr, nt, nb), (cl, cr, ct, cb))
    index = bytearray()
    body = bytearray()
    n_blocks = (cr - cl + 1) * (cb - ct + 1)
    for y in range(ct, cb + 1):
        for x in range(cl, cr + 1):
            raw, water = block(x, y)
            offset = 5 * n_blocks + len(body)
            index += (offset | (0x8000000000 if water else 0)).to_bytes(5, 'big')
            body += raw
    sub = bytes(index + body)

    def header(file_size, sub_start):
        h = struct.pack('>i', version) + struct.pack('>q', file_size) + data[36:44]
        h += struct.pack('>iiii', *micro) + struct.pack('>h', tile_size) + enc_string(projection)
        h += bytes([flags]) + optional + tag_lists + bytes([1])
        h += bytes([base, lo, hi]) + struct.pack('>qq', sub_start, len(sub))
        return data[:20] + struct.pack('>i', len(h)) + h

    first = header(0, 0)
    head = header(len(first) + len(sub), len(first))
    assert len(head) == len(first)
    open(dst, 'wb').write(head + sub)
    print(f'{dst}: {n_blocks} blocks at zoom {base} ({cr - cl + 1} x {cb - ct + 1}), '
          f'{len(head) + len(sub)} bytes, box {[v / 1e6 for v in micro]}')


if __name__ == '__main__':
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2], *map(float, sys.argv[3:]))
