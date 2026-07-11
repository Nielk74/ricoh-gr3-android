#!/usr/bin/env python3
"""Generate the tileable film-grain plate used by the develop engine.

The engine composites this fixed grain "plate" over developed images (soft-light,
tiled) instead of synthesising grain per pixel — the same technique production
film-emulation tools use, which is what makes grain read as film rather than
digital noise. The plate is generated (not a third-party scan) so it is
unambiguously license-clean to ship in this repo.

Method: gaussian-ish white noise → wrap-around (toroidal) gaussian blur so the
tile is seamless → normalise → map to 8-bit grey centred on 128. Two scales are
summed for a slightly organic (non-uniform) grain structure.

Run:  python3 tools/generate_grain_plate.py
Out:  app/src/main/assets/grain/grain_35mm.png   (512x512, grayscale)
"""
import os
import random

from PIL import Image

S = 512
SEED = 20240712


def box_blur_wrap(buf, s, r):
    if r <= 0:
        return buf
    out = [0.0] * (s * s)
    norm = 1.0 / (2 * r + 1)
    for y in range(s):
        row = y * s
        for x in range(s):
            acc = 0.0
            for k in range(-r, r + 1):
                acc += buf[row + ((x + k) % s)]
            out[row + x] = acc * norm
    buf2, out = out, [0.0] * (s * s)
    for x in range(s):
        for y in range(s):
            acc = 0.0
            for k in range(-r, r + 1):
                acc += buf2[((y + k) % s) * s + x]
            out[y * s + x] = acc * norm
    return out


def gauss_wrap(buf, s, r):
    for _ in range(3):
        buf = box_blur_wrap(buf, s, r)
    return buf


def main():
    random.seed(SEED)
    base = [(random.random() - 0.5) + (random.random() - 0.5) for _ in range(S * S)]
    fine = gauss_wrap(base[:], S, 1)
    mid = gauss_wrap(base[:], S, 2)
    g = [fine[i] + 0.35 * mid[i] for i in range(S * S)]

    m = sum(g) / len(g)
    std = (sum((v - m) ** 2 for v in g) / len(g)) ** 0.5
    scale = 42.0 / std  # ~±2.5σ fills the 8-bit range around 128
    px = bytearray(S * S)
    for i, v in enumerate(g):
        px[i] = max(0, min(255, int(128 + (v - m) * scale + 0.5)))

    out = os.path.join("app", "src", "main", "assets", "grain", "grain_35mm.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    Image.frombytes("L", (S, S), bytes(px)).save(out, optimize=True)
    print("wrote", out)


if __name__ == "__main__":
    main()
