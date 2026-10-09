#!/usr/bin/env python3
"""
Via's Play Store feature graphic: store/feature_graphic.png, 1024 x 500.

    pip install shapely skia-python pillow numpy
    python3 tools/make_feature_graphic.py

The iris (tools/make_icon.py, the same geometry as the launcher icon) on a
deep navy night, sending out the beat as rings over a waveform that rises
under it - the reactive artwork, still - beside the name in Fraunces and the
tagline in Inter, the app's own fonts.

Drawn at twice the size and scaled down, so edges and text are clean. Play
asks for a 24-bit PNG with no transparency, which is what this writes. The
text keeps more than 50px from every edge.
"""
import math
import os
import sys

import numpy as np
import skia
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_icon as icon  # noqa: E402

FONTS = "app/src/main/res/font/"
OUT = "store/feature_graphic.png"
W, H, K = 1024, 500, 2

# The mark's centre and radius, and the text block's left edge.
MX, MY, MR = 280, 250, 142
TX = 540
TAGLINE = "Your library, lit by what’s playing."
FEATURES = "REACTIVE ARTWORK  ·  DEPTH & SPACE SOUND"
TEAL = "#5EEAD4"


def mark(cx, cy, radius):
    d = icon.poly_d(icon.mark(), lambda c: c, 2)
    k = radius / icon.R
    t = f"translate({cx - icon.CX * k:.2f},{cy - icon.CY * k:.2f}) scale({k:.5f})"
    return (f'<g transform="translate(0,{radius * 0.08:.1f})"><path d="{d}" transform="{t}" fill="{icon.SHADOW}" '
            f'fill-opacity="0.34" fill-rule="evenodd" filter="url(#sh)"/></g>'
            f'<path d="{d}" transform="{t}" fill="url(#w)" fill-rule="evenodd"/>')


def picture():
    defs = ('<linearGradient id="bg" x1="0" y1="0" x2="1024" y2="500" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#0B2A44"/><stop offset="0.55" stop-color="#0A1B3D"/><stop offset="1" stop-color="#070F26"/></linearGradient>'
            f'<radialGradient id="glow" cx="{MX}" cy="{MY}" r="330" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#22D3C5" stop-opacity="0.55"/>'
            '<stop offset="0.5" stop-color="#0EA5B7" stop-opacity="0.18"/><stop offset="1" stop-color="#0EA5B7" stop-opacity="0"/></radialGradient>'
            '<filter id="sh" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="16"/></filter>'
            f'<linearGradient id="w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{icon.MARK_TOP}"/>'
            f'<stop offset="1" stop-color="{icon.MARK_BOTTOM}"/></linearGradient>')
    body = '<rect width="1024" height="500" fill="url(#bg)"/><rect width="1024" height="500" fill="url(#glow)"/>'
    # The beat leaving the mark: three rings, each fainter and further out.
    for r, a, w in [(170, 0.55, 5), (206, 0.32, 4), (244, 0.17, 3)]:
        body += f'<circle cx="{MX}" cy="{MY}" r="{r}" fill="none" stroke="{TEAL}" stroke-opacity="{a}" stroke-width="{w}"/>'
    # A waveform along the bottom, rising under the mark. Seeded: the same
    # picture every time.
    rng = np.random.default_rng(4)
    for i in range(96):
        x = 24 + i * 10.4
        env = 0.25 + 0.75 * math.exp(-((x - MX) / 230) ** 2)
        h = 8 + 34 * env * (0.45 + 0.55 * abs(math.sin(i * 0.55) * rng.uniform(0.6, 1.0)))
        body += (f'<rect x="{x:.1f}" y="{486 - h:.1f}" width="5" height="{h:.1f}" rx="2.5" '
                 f'fill="{TEAL}" fill-opacity="{0.18 + 0.4 * env:.2f}"/>')
    body += mark(MX, MY, MR)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
           f"<defs>{defs}</defs>{body}</svg>")
    dom = skia.SVGDOM.MakeFromStream(skia.MemoryStream(svg.encode(), True))
    surface = skia.Surface(W * K, H * K)
    with surface as c:
        c.scale(K, K)
        dom.render(c)
    pixels = surface.makeImageSnapshot().toarray(colorType=skia.kRGBA_8888_ColorType)
    return Image.fromarray(np.asarray(pixels)).convert("RGB")


def font(name, px, weight, optical):
    f = ImageFont.truetype(FONTS + name, px * K)
    values = []
    for axis in f.get_variation_axes():
        n = axis["name"] if isinstance(axis["name"], str) else axis["name"].decode()
        if n.lower().startswith("optical"):
            values.append(optical)
        elif n == "Weight":
            values.append(weight)
        elif n == "Wonky":
            values.append(0)
        else:
            values.append(axis["default"])
    f.set_variation_by_axes(values)
    return f


def text(img, x, y, s, f, fill, track=0.0):
    d = ImageDraw.Draw(img)
    x, y = x * K, y * K
    if not track:
        d.text((x, y), s, font=f, fill=fill, anchor="ls")
        return
    for ch in s:
        d.text((x, y), ch, font=f, fill=fill, anchor="ls")
        x += d.textlength(ch, font=f) + track * K


def main():
    if not os.path.isdir(FONTS):
        sys.exit("run from the repository root")
    img = picture()
    text(img, TX, 250, "Via", font("fraunces_variable.ttf", 150, 560, 144), (255, 255, 255))
    text(img, TX + 6, 306, TAGLINE, font("inter_variable.ttf", 30, 500, 32), (235, 250, 252))
    text(img, TX + 7, 356, FEATURES, font("inter_variable.ttf", 15, 600, 14), (110, 231, 214), track=1.6)
    os.makedirs("store", exist_ok=True)
    img.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
    print("  " + OUT)


if __name__ == "__main__":
    main()
