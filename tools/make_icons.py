#!/usr/bin/env python3
"""
Generates every launcher/store icon from ONE definition, so the home-screen
icon, the round mask, the API 24-25 fallback and the Play listing can never
drift apart.

    python3 tools/make_icons.py
"""
import os
from PIL import Image, ImageDraw

# Google guarantees only the inner 66dp of the 108dp canvas survives masking
# (r <= 33 from centre). Skins like MIUI scale further on top, so the outer
# stroke edge sits at 28.8 — 4.2dp of headroom.
SRC, CENTRE = 108.0, 54.0
R_OUT, W_OUT, A_OUT = 27.5, 2.6, 0.32
R_IN,  W_IN,  A_IN  = 18.1, 3.0, 0.55
DOT_R = 7.5        # centre anchor: without it the mark reads as an empty frame
BG   = (0x20, 0x13, 0x46, 255)
RING = (0x9B, 0x7C, 0xFF)
VISIBLE = 33.0     # radius the launcher guarantees; store icon crops to this

RES = "app/src/main/res"

def render(px, ss=4, crop=None):
    C = int(px * ss); K = C / SRC
    def p(v): return v * K
    img = Image.new("RGBA", (C, C), BG)
    for r, w, a in ((R_OUT, W_OUT, A_OUT), (R_IN, W_IN, A_IN)):
        lay = Image.new("RGBA", (C, C), (0, 0, 0, 0))
        ImageDraw.Draw(lay).ellipse(
            [p(CENTRE - r), p(CENTRE - r), p(CENTRE + r), p(CENTRE + r)],
            outline=RING + (int(a * 255),), width=max(1, int(p(w))))
        img = Image.alpha_composite(img, lay)
    ImageDraw.Draw(img).ellipse(
        [p(CENTRE - DOT_R), p(CENTRE - DOT_R), p(CENTRE + DOT_R), p(CENTRE + DOT_R)],
        fill=(255, 255, 255, 255))
    if crop:
        lo, hi = p(CENTRE - crop), p(CENTRE + crop)
        img = img.crop((int(lo), int(lo), int(hi), int(hi)))
    return img.resize((px, px), Image.LANCZOS)

def circular(img):
    m = Image.new("L", (img.width * 4, img.height * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, m.width, m.height], fill=255)
    out = img.copy(); out.putalpha(m.resize(img.size, Image.LANCZOS)); return out

# minSdk is 24, but mipmap-anydpi-v26 only applies from API 26 — without these
# Android 7.0/7.1 has NO launcher icon resource at all.
for d, px in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    out = f"{RES}/mipmap-{d}"; os.makedirs(out, exist_ok=True)
    base = render(px)
    base.convert("RGB").save(f"{out}/ic_launcher.png")
    circular(base).save(f"{out}/ic_launcher_round.png")
    print(f"  mipmap-{d:9} {px}x{px}")

os.makedirs("store", exist_ok=True)
render(512, crop=VISIBLE).convert("RGB").save("store/play_icon_512.png")
print("  store/play_icon_512.png  512x512 opaque")
