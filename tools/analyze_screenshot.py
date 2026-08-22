#!/usr/bin/env python3
"""Analyze Android screencap PNGs without a display.

Usage:
  python3 analyze_screenshot.py shot.png                 ascii art + content extent
  python3 analyze_screenshot.py shot.png --extent        content bounding box only
  python3 analyze_screenshot.py a.png --diff b.png       fraction of changed pixels

Useful when developing headless: verify the TV actually rendered pixels,
measure where content sits (pillarbox geometry), or confirm video frames
are advancing (two captures differ).
"""
import argparse
import struct
import zlib


def load(path):
    data = open(path, "rb").read()
    if not data.startswith(b"\x89PNG"):
        raise SystemExit(f"{path}: not a PNG")
    pos = 8
    w = h = None
    bpp = 4
    idat = b""
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        ct = data[pos + 4:pos + 8]
        if ct == b"IHDR":
            w, h = struct.unpack(">II", data[pos + 8:pos + 16])
            color_type = data[pos + 17]         # IHDR: 4 len + 4 type + 4 w + 4 h + 1 depth
            bpp = 3 if color_type == 2 else 4   # RGB (browser PNGs) vs RGBA (screencap)
        elif ct == b"IDAT":
            idat += data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * bpp + 1

    def px(x, y):
        i = y * stride + 1 + x * bpp
        return raw[i], raw[i + 1], raw[i + 2]

    return w, h, px


def ascii_art(path, cols=96, lines=36):
    w, h, px = load(path)
    print(f"{path}: {w}x{h}")
    for ly in range(lines):
        y = int(ly * h / lines)
        row = []
        for lx in range(cols):
            x = int(lx * w / cols)
            r, g, b = px(x, y)
            v = (r + g + b) / 3
            row.append(" " if v < 30 else ("." if v < 90 else ("+" if v < 170 else "#")))
        print("".join(row))


def extent(path, threshold=25, step=3):
    w, h, px = load(path)
    minx, maxx, miny, maxy = w, 0, h, 0
    for y in range(0, h, step):
        for x in range(0, w, step):
            r, g, b = px(x, y)
            if r > threshold or g > threshold or b > threshold:
                minx = min(minx, x)
                maxx = max(maxx, x)
                miny = min(miny, y)
                maxy = max(maxy, y)
    if maxx <= minx:
        print(f"no content above threshold {threshold} (screen effectively black)")
        return
    cw, ch = maxx - minx, maxy - miny
    print(f"content x[{minx}..{maxx}] y[{miny}..{maxy}] = {cw}x{ch} aspect={cw / ch:.3f}")


def diff(a, b, threshold=40, step=6):
    wa, ha, pa = load(a)
    wb, hb, pb = load(b)
    if (wa, ha) != (wb, hb):
        print(f"warning: sizes differ {wa}x{ha} vs {wb}x{hb}")
    d = t = 0
    for y in range(0, min(ha, hb), step):
        for x in range(0, min(wa, wb), step):
            t += 1
            ca, cb = pa(x, y), pb(x, y)
            if abs(ca[0] - cb[0]) + abs(ca[1] - cb[1]) + abs(ca[2] - cb[2]) > threshold:
                d += 1
    print(f"differing pixels: {d}/{t} ({100 * d / t:.1f}%)")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("png")
    p.add_argument("--ascii", action="store_true")
    p.add_argument("--extent", action="store_true")
    p.add_argument("--diff", metavar="other_png")
    args = p.parse_args()
    if not (args.ascii or args.extent or args.diff):
        args.ascii = True
        args.extent = True
    if args.ascii:
        ascii_art(args.png)
    if args.extent:
        extent(args.png)
    if args.diff:
        diff(args.png, args.diff)
