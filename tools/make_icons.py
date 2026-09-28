#!/usr/bin/env python3
"""
Every launcher, splash and header asset, generated from ONE photograph, so the
home-screen icon, the round mask, the themed icon, the API 24-25 fallback, the
splash and the Play listing can never drift apart.

    pip install pillow numpy
    python3 tools/make_icons.py

SOURCE  art/hummingbird.png - the original, committed but never shipped: it
lives outside app/src/main/res, so it is not in the APK.

The mark is the hummingbird, cut out of its background and placed on deep
black. Three rules shaped the geometry:

  FULL BLEED BLACK. The plate fills the whole 108dp layer. Adaptive icons do
  not keep their own shape - the launcher applies its own mask, and Samsung's
  is a squircle. Black is @color/window_ink, the same black the app itself
  sits on, so icon and app are one surface.

  SAFE RADIUS. Everything visible is scaled so that no opaque pixel sits more
  than SAFE_DP from the centre. At 34dp that is a 68dp circle inside the 72dp
  the mask actually shows - the outer ring holds nothing but the translucent
  tip of one wing and the point of the beak.

  ONE SILHOUETTE. The themed (monochrome) icon is the same alpha channel,
  flattened. It cannot drift from the colour icon because it IS the colour
  icon's coverage.

The background is removed by fitting a smooth 2D polynomial to the border of
the photograph and keeping the pixels that do not match it. The studio
backdrop is a soft gradient, so the fit is near-exact: on the source shipped
with this repo the background residual is under 14/255 while the bird is over
190, which is why a fixed threshold is safe here. The script refuses to write
anything if that separation is not found.
"""
import math
import os
import random
import sys
from collections import deque

try:
    import numpy as np
    from PIL import Image, ImageFilter
except ImportError:
    sys.exit("needs pillow and numpy:  pip install pillow numpy")

SOURCE = "art/hummingbird.png"
RES    = "app/src/main/res"

CANVAS_DP = 108.0          # adaptive icon layer
SAFE_DP   = 34.0           # max radius of any opaque pixel, from centre
MARK_DP   = 96.0           # tallest place the mark is drawn (Settings)

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0),
             ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]


# --------------------------------------------------------------- cut out
def refine_background(a, basis, sel, tol=26.0, rounds=6):
    """Fit the backdrop, then re-fit on what the fit itself calls backdrop.

    Fitting the border frame alone is not enough. The frame is 6% of the
    image and all of it is at the edge, so the polynomial is extrapolating
    across the middle - and it was wrong there by enough that two patches of
    plain backdrop, one above the head and one under the wing, came out as
    subject and shipped inside the icon as blue lumps on black.

    Feeding the fit every pixel it currently believes is backdrop turns
    extrapolation into interpolation. Two rounds is usually enough; it stops
    as soon as the classification stops moving.
    """
    H, W, _ = a.shape
    bg = np.zeros_like(a)
    d = np.zeros((H, W))
    for _ in range(rounds):
        for c in range(3):
            coef, _r, _k, _s = np.linalg.lstsq(basis[sel], a[..., c][sel], rcond=None)
            bg[..., c] = basis @ coef
        d = np.sqrt(((a - bg) ** 2).sum(-1))
        nxt = d < tol
        moved = int((nxt != sel).sum())
        sel = nxt
        if moved < 200:
            break
    return d, sel


def cutout(path):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    if min(W, H) < 512:
        sys.exit("source is %dx%d - needs to be at least 512x512" % (W, H))
    a = np.asarray(im, np.float64)

    yy, xx = np.mgrid[0:H, 0:W]
    X = xx / (W - 1.0) * 2 - 1
    Y = yy / (H - 1.0) * 2 - 1
    # Degree 6. A studio backdrop is a smooth field, not a plane; degree 4
    # could not bend enough to follow this one across the frame.
    terms = [(i, j) for i in range(7) for j in range(7) if i + j <= 6]
    basis = np.stack([(X ** i) * (Y ** j) for i, j in terms], -1)

    f = max(4, int(min(W, H) * 0.06))
    seed = np.zeros((H, W), bool)
    seed[:f] = seed[-f:] = True
    seed[:, :f] = seed[:, -f:] = True

    d, background = refine_background(a, basis, seed)
    resid = float(np.percentile(d[background], 99.9))
    subject = float(np.percentile(d, 92))
    print("  backdrop settled at %.1f%% of the frame, residual %.1f, subject %.1f"
          % (100 * background.mean(), resid, subject))
    if subject < resid * 6:
        sys.exit("subject does not separate from the backdrop - is %s the "
                 "right image, and is its backdrop a smooth gradient?" % path)

    # Band sits above the residual the fit actually leaves, not a guess.
    lo, hi = max(24.0, resid * 1.15), max(60.0, resid * 2.6)
    soft = np.clip((d - lo) / (hi - lo), 0, 1)
    hard = d > (lo + hi) / 2

    # largest connected blob = the bird; everything else is dust
    lab = np.zeros(hard.shape, np.int32)
    n = 0
    best = (0, 0)
    for sy in range(H):
        for sx in np.nonzero(hard[sy] & (lab[sy] == 0))[0]:
            n += 1
            cnt = 0
            q = deque([(sy, int(sx))])
            lab[sy, sx] = n
            while q:
                y, x = q.popleft()
                cnt += 1
                for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                    if 0 <= ny < H and 0 <= nx < W and hard[ny, nx] and lab[ny, nx] == 0:
                        lab[ny, nx] = n
                        q.append((ny, nx))
            if cnt > best[0]:
                best = (cnt, n)
    body = lab == best[1]
    frac = best[0] / float(W * H)
    print("  subject fills %.1f%% of the frame" % (frac * 100))
    if not 0.05 < frac < 0.75:
        sys.exit("that blob is %.1f%% of the frame - refusing to build icons "
                 "from it" % (frac * 100))

    # fill enclosed holes (the eye) by flooding the backdrop in from the edge
    inv = ~body
    seen = np.zeros(inv.shape, bool)
    q = deque()
    for x in range(W):
        for y in (0, H - 1):
            if inv[y, x] and not seen[y, x]:
                seen[y, x] = True
                q.append((y, x))
    for y in range(H):
        for x in (0, W - 1):
            if inv[y, x] and not seen[y, x]:
                seen[y, x] = True
                q.append((y, x))
    while q:
        y, x = q.popleft()
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < H and 0 <= nx < W and inv[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                q.append((ny, nx))
    body = body | (inv & ~seen)

    # soft alpha only in a narrow band around the body
    band = np.asarray(Image.fromarray((body * 255).astype("uint8"))
                      .filter(ImageFilter.MaxFilter(7))) > 0
    alpha = np.where(body, 1.0, np.where(band, soft, 0.0))

    rgba = np.dstack([np.asarray(im, np.uint8), (alpha * 255).astype("uint8")])
    ys, xs = np.nonzero(alpha > 0.02)
    return Image.fromarray(rgba, "RGBA").crop(
        (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))


# ------------------------------------------- smallest circle holding it all
def enclosing_circle(img):
    """Welzl. Placing THIS circle's centre at the icon centre, rather than the
    bounding box's, is what lets the bird run 17% larger at the same safety:
    the box corners are empty, the circle's are not."""
    a = np.asarray(img)[..., 3] > 8
    inner = np.asarray(Image.fromarray((a * 255).astype("uint8"))
                       .filter(ImageFilter.MinFilter(3))) > 0
    ys, xs = np.nonzero(a & ~inner)
    pts = list(zip(xs.tolist(), ys.tolist()))
    pts = pts[::max(1, len(pts) // 4000)]

    def c2(p, q):
        return ((p[0] + q[0]) / 2, (p[1] + q[1]) / 2,
                math.hypot(p[0] - q[0], p[1] - q[1]) / 2)

    def c3(p, q, s):
        d = 2 * (p[0] * (q[1] - s[1]) + q[0] * (s[1] - p[1]) + s[0] * (p[1] - q[1]))
        if abs(d) < 1e-12:
            return None
        pp, qq, ss = (p[0] ** 2 + p[1] ** 2), (q[0] ** 2 + q[1] ** 2), (s[0] ** 2 + s[1] ** 2)
        ux = (pp * (q[1] - s[1]) + qq * (s[1] - p[1]) + ss * (p[1] - q[1])) / d
        uy = (pp * (s[0] - q[0]) + qq * (p[0] - s[0]) + ss * (q[0] - p[0])) / d
        return (ux, uy, math.hypot(p[0] - ux, p[1] - uy))

    def holds(c, p):
        return c is not None and math.hypot(p[0] - c[0], p[1] - c[1]) <= c[2] + 1e-7

    P = pts[:]
    random.Random(7).shuffle(P)
    c = None
    for i, p in enumerate(P):
        if holds(c, p):
            continue
        c = (p[0], p[1], 0.0)
        for j in range(i):
            if holds(c, P[j]):
                continue
            c = c2(p, P[j])
            for k in range(j):
                if holds(c, P[k]):
                    continue
                c = c3(p, P[j], P[k]) or c
    return c


# --------------------------------------------------------------- rendering
def foreground(px, bird, circle):
    """Bird alone, transparent, on a CANVAS_DP square, extremities at SAFE_DP."""
    cx, cy, r = circle
    k = (SAFE_DP / CANVAS_DP * px) / r
    b = bird.resize((max(1, round(bird.width * k)), max(1, round(bird.height * k))),
                    Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.alpha_composite(b, (round(px / 2 - cx * k), round(px / 2 - cy * k)))
    return out


def on_black(fg):
    out = Image.new("RGBA", fg.size, (0, 0, 0, 255))
    out.alpha_composite(fg)
    return out


def monochrome(fg):
    """Same coverage, flattened to white. Android tints it for themed icons."""
    a = fg.split()[3].point(lambda v: 255 if v >= 96 else v * 255 // 96)
    return Image.merge("RGBA", (Image.new("L", fg.size, 255),) * 3 + (a,))


def circle_mask(img):
    from PIL import ImageDraw
    s = img.size[0]
    m = Image.new("L", (s * 4, s * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, s * 4, s * 4], fill=255)
    out = img.copy()
    out.putalpha(m.resize(img.size, Image.LANCZOS))
    return out


def write(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    return os.path.getsize(path)


if __name__ == "__main__":
    if not os.path.exists(SOURCE):
        sys.exit("missing %s - put the hummingbird photograph there first" % SOURCE)
    print("reading", SOURCE)
    bird = cutout(SOURCE)
    circle = enclosing_circle(bird)
    print("  cut to %dx%d, enclosing circle r=%.0f at (%.0f,%.0f)"
          % (bird.width, bird.height, circle[2], circle[0], circle[1]))

    total = 0
    for name, mult in DENSITIES:
        px = int(round(CANVAS_DP * mult))
        fg = foreground(px, bird, circle)
        total += write(fg, "%s/drawable-%s/ic_launcher_foreground.png" % (RES, name))
        total += write(monochrome(fg), "%s/drawable-%s/ic_launcher_monochrome.png" % (RES, name))

        legacy = int(round(48 * mult))
        lg = on_black(foreground(legacy, bird, circle))
        total += write(lg.convert("RGB"), "%s/mipmap-%s/ic_launcher.png" % (RES, name))
        total += write(circle_mask(lg), "%s/mipmap-%s/ic_launcher_round.png" % (RES, name))
        print("  %-8s foreground %dpx   legacy %dpx" % (name, px, legacy))

    # Header mark: one xxxhdpi asset, downscaled by the platform everywhere
    # else. It is drawn at a fixed 54dp, so a second copy per density would be
    # ~200KB of APK for pixels nobody sees.
    h = int(round(MARK_DP * 4))
    w = max(1, round(bird.width * h / bird.height))
    total += write(bird.resize((w, h), Image.LANCZOS), "%s/drawable-xxxhdpi/aura_mark.png" % RES)
    print("  header mark %dx%d (xxxhdpi)" % (w, h))

    total += write(on_black(foreground(512, bird, circle)).convert("RGB"),
                   "store/play_icon_512.png")
    print("  store/play_icon_512.png")
    print("shipped assets: %.0f KB" % (total / 1024.0))
