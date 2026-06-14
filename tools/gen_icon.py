#!/usr/bin/env python3
"""Generate the launcher icon (512x512 PNG, mipmap-xxxhdpi) for Portal.
Pure stdlib (zlib + struct), no PIL. Portal's launcher renders the icon as a
colored tile and needs a PNG in mipmap-xxxhdpi (adaptive-only icons are hidden).
A "photo" motif in the Portal palette: blue field, lemon sun, layered mountains.
Output dir overridable as argv[1]."""
import zlib, struct, os, sys

S = 512  # Portal tile icon: 512x512 PNG in mipmap-xxxhdpi
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "app", "res", "mipmap-xxxhdpi")
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

# Portal palette
BLUE = (0x19, 0x90, 0xFF)      # primary blue field
SUN = (0xFC, 0xD8, 0x72)       # lemon
TEAL = (0x6B, 0xCE, 0xBB)      # back hill
SNOW = (0xF0, 0xF0, 0xF0)      # near-white front mountains


def chunk(typ, data):
    return (struct.pack(">I", len(data)) + typ + data +
            struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))


def make_icon(path):
    px = [bytearray(bytes(BLUE) * S) for _ in range(S)]

    def put(x, y, c):
        if 0 <= x < S and 0 <= y < S:
            px[y][x * 3:x * 3 + 3] = bytes(c)

    # Sun (filled circle), upper area.
    cx, cy, r = int(S * 0.355), int(S * 0.33), int(S * 0.115)
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                put(x, y, SUN)

    base_y = int(S * 0.84)

    # Back hill (teal) for depth.
    for (pxk, pyk, hb, col) in [
            (int(S * 0.62), int(S * 0.40), int(S * 0.40), TEAL),  # teal back
            (int(S * 0.34), int(S * 0.46), int(S * 0.30), SNOW),  # white left
            (int(S * 0.66), int(S * 0.52), int(S * 0.34), SNOW)]: # white right
        for y in range(pyk, base_y):
            t = (y - pyk) / float(base_y - pyk)
            half = int(hb * t)
            for x in range(pxk - half, pxk + half + 1):
                put(x, y, col)

    raw = bytearray()
    for row in px:
        raw.append(0)
        raw.extend(row)
    comp = zlib.compress(bytes(raw), 9)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", S, S, 8, 2, 0, 0, 0)  # 8-bit truecolor RGB
    png = sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return path


p = make_icon(os.path.join(OUT, "ic_launcher.png"))
print("wrote", p)
