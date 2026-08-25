#!/usr/bin/env python3
"""
Renders the splash branding wordmark from the app's own Inter font.

VectorDrawable has no text element, so the branding image must be raster.
Re-run this whenever the name or version changes:
    python3 tools/make_wordmark.py "AURA 2.1"
"""
import sys
from PIL import Image, ImageDraw, ImageFont

TEXT = sys.argv[1] if len(sys.argv) > 1 else "AURA 2.0"
FONT = "app/src/main/res/font/inter_variable.ttf"
OUT  = "app/src/main/res/drawable-xxhdpi/splash_branding.png"

# Android caps branding at 200x80dp. xxhdpi = 3x, so 600x240px.
W, H, PX, TRACK = 600, 240, 46, 8

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
f = ImageFont.truetype(FONT, PX)
try:
    f.set_variation_by_axes([600])      # semibold if the axis exists
except Exception:
    pass

widths = [d.textlength(c, font=f) for c in TEXT]
total = sum(widths) + TRACK * (len(TEXT) - 1)
x = (W - total) / 2
box = d.textbbox((0, 0), TEXT, font=f)
y = (H - (box[3] - box[1])) / 2 - box[1]

for c, w in zip(TEXT, widths):
    d.text((x, y), c, font=f, fill=(255, 255, 255, 235))
    x += w + TRACK

img.save(OUT)
print(f"wrote {OUT}  {W}x{H}  text={TEXT!r}  ink width={total:.0f}px")
