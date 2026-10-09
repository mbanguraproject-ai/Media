#!/usr/bin/env python3
"""
Via's icon - the aperture - and every asset made from it.

    pip install shapely skia-python pillow numpy
    python3 tools/make_icon.py

THE MARK - THE IRIS
A white disc cut into three identical blades closing around a play-shaped
opening: a camera iris (picture) opening onto play (sound). Each side of the
play shape carries straight on past its corner and bends out to the rim, so
the cuts and the opening are one continuous line - the blades' own edges
make the play shape. Exact three-way symmetry - one cut, rotated by 120 and
240 degrees - so nothing is drawn by hand. The numbers below are the design;
change them here and nothing else.

Every asset is generated from that one geometry, so the launcher, the themed
icon, the header mark, the splash and the Play listing cannot drift apart:

  art/via-icon.svg                              master, 1024, full bleed
  res/drawable/ic_launcher_background.xml       adaptive layers, as vectors:
  res/drawable/ic_launcher_foreground.xml         sharp at every size and mask
  res/drawable/ic_launcher_monochrome.xml       themed icon (Android 13+)
  res/drawable/via_mark.xml                     the icon as a tile, for Home and About
  res/drawable/ic_stat_via.xml                  status bar and notifications (also as
                                                  media3_notification_small_icon)
  res/drawable/splash_icon.xml                  splash, Android 11 and below (still)
  res/drawable/splash_icon_animated.xml         splash, Android 12+: the iris opens
  res/mipmap-*/ic_launcher(_round).png          Android 7.x, which has no adaptive icons
  store/play_icon_512.png                       the Play listing

THE SPLASH
The iris starts closed - three curved lines meeting near the centre - turns
a quarter turn into place and opens onto the play shape, over a lit disc
that grows in under it. Each blade is a true shape morph (the same blade at
two opening sizes, resampled to the same points), not a cover sliding off,
so every frame is the mark itself.
"""
import math
import os
import sys

from shapely import affinity
from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

RES = "app/src/main/res"

# ------------------------------------------------------------------ design
CX = CY = 512.0
R = 300.0          # the disc
S = 176.0          # the opening: circumradius of the play triangle
CUT = 22.0         # width of each cut
END = 62.0         # where each cut leaves the rim, degrees round from its corner
BEND = 0.55        # how far the cut has turned along the rim as it leaves (0 straight out, 1 along it)
SOFT = 7.0         # the rounding of every corner of the blades
CLOSED = 60.0      # the opening's size when the splash starts

# The palette: teal into deep blue, lit from the top left; the mark white.
BG_STOPS = [(0.0, "#3BE8CB"), (0.48, "#0EA5B7"), (1.0, "#1E3A8A")]
MARK_TOP, MARK_BOTTOM = "#FFFFFF", "#E2F2FF"
SHADOW = "#04122B"


def soften(p, r):
    """Every corner rounded by [r], the outward ones and the inward ones."""
    p = p.buffer(-r, join_style="round").buffer(r, join_style="round")
    return p.buffer(r * 0.6, join_style="round").buffer(-r * 0.6, join_style="round")


def triangle(s=S):
    return [(CX + s * math.cos(math.radians(120 * k)), CY + s * math.sin(math.radians(120 * k))) for k in range(3)]


def cuts(s=S):
    # One cut. Its outer edge is the play shape's side, carried on past the
    # corner (the centre line runs half a cut inside it), then a cubic
    # curve bends it out to the rim, leaving at END degrees and turned BEND
    # of the way from straight out towards along the rim.
    v = triangle(s)
    v0, v2 = v[0], v[2]
    ln = math.dist(v0, v2)
    d = ((v0[0] - v2[0]) / ln, (v0[1] - v2[1]) / ln)
    n = (-d[1], d[0])
    if (CX - v0[0]) * n[0] + (CY - v0[1]) * n[1] < 0:
        n = (-n[0], -n[1])
    h2 = CUT / 2
    p0 = (v0[0] + n[0] * h2 - d[0] * 60, v0[1] + n[1] * h2 - d[1] * 60)   # starts inside the opening
    p1 = (v0[0] + n[0] * h2 + d[0] * 40, v0[1] + n[1] * h2 + d[1] * 40)
    a = math.radians(END)
    e = (CX + (R + 34) * math.cos(a), CY + (R + 34) * math.sin(a))
    rad = (math.cos(a), math.sin(a))
    tan = (-rad[1], rad[0])
    hx, hy = rad[0] * (1 - BEND) + tan[0] * BEND, rad[1] * (1 - BEND) + tan[1] * BEND
    hl = math.hypot(hx, hy)
    hx, hy = hx / hl, hy / hl
    q1 = (p1[0] + d[0] * 110, p1[1] + d[1] * 110)
    q2 = (e[0] - hx * 120, e[1] - hy * 120)
    pts = [p0]
    for i in range(1, 161):
        t = i / 160
        u = 1 - t
        pts.append((u ** 3 * p1[0] + 3 * u * u * t * q1[0] + 3 * u * t * t * q2[0] + t ** 3 * e[0],
                    u ** 3 * p1[1] + 3 * u * u * t * q1[1] + 3 * u * t * t * q2[1] + t ** 3 * e[1]))
    cut = LineString(pts).buffer(CUT / 2, cap_style="flat")
    return unary_union([affinity.rotate(cut, 120 * k, origin=(CX, CY)) for k in range(3)])


def mark(s=S):
    """The mark: three blades, in order round the centre, each one closed outline."""
    disc = Point(CX, CY).buffer(R, 128)
    m = soften(disc.difference(cuts(s)).difference(Polygon(triangle(s))), SOFT)
    blades = list(getattr(m, "geoms", [m]))
    if len(blades) != 3 or any(len(b.interiors) for b in blades):
        sys.exit("the mark should be three blades; got %s" % m.geom_type)
    blades.sort(key=lambda b: math.atan2(b.centroid.y - CY, b.centroid.x - CX))
    # Points closer than a quarter of a design unit to the line through their
    # neighbours go: a sixteenth of a pixel on the 512px store icon.
    return MultiPolygon([orient(b.simplify(0.25, preserve_topology=True), 1.0) for b in blades])


# ------------------------------------------------------------ path data
def ring_d(coords, f):
    pts = list(coords)[:-1]
    return "M" + " L".join("%s,%s" % (f(x), f(y)) for x, y in pts) + "Z"


def poly_d(m, tx, nd=2):
    """Path data for every blade, each its own closed subpath."""
    f = lambda v: ("%." + str(nd) + "f") % v
    out = []
    for p in getattr(m, "geoms", [m]):
        out.append(ring_d([tx(c) for c in p.exterior.coords], f))
        for ring in p.interiors:
            out.append(ring_d([tx(c) for c in ring.coords], f))
    return "".join(out)


def resample(coords, n, anchor):
    """[n] points evenly along a closed ring, starting nearest [anchor]."""
    pts = list(coords)[:-1]
    k = min(range(len(pts)), key=lambda i: (pts[i][0] - anchor[0]) ** 2 + (pts[i][1] - anchor[1]) ** 2)
    pts = pts[k:] + pts[:k] + [pts[k]]
    seg = [math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    total = sum(seg)
    out, i, acc = [], 0, 0.0
    for j in range(n):
        target = total * j / n
        while acc + seg[i] < target:
            acc += seg[i]
            i += 1
        t = (target - acc) / seg[i] if seg[i] else 0.0
        out.append((pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t))
    return out + [out[0]]


def morph_d(m, anchors, tx, n=220):
    """Path data with a fixed number of points per blade, each blade starting at
    its own [anchors] point, so the same blade at two sizes can morph."""
    f = lambda v: "%.2f" % v
    return "".join(ring_d([tx(c) for c in resample(b.exterior.coords, n, a)], f)
                   for b, a in zip(m.geoms, anchors))


def rim_anchors(m):
    """A point on the rim in the middle of each blade: where its morph starts."""
    out = []
    for b in m.geoms:
        a = math.atan2(b.centroid.y - CY, b.centroid.x - CX)
        out.append((CX + R * math.cos(a), CY + R * math.sin(a)))
    return out


# ------------------------------------------------------- VectorDrawable
HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/make_icon.py - edit that, not this. -->\n")


def vd_open(size_dp, viewport, extra=""):
    return (HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}"{extra}>\n')


def grad_linear(x1, y1, x2, y2, stops, ind="        "):
    s = f'{ind}<aapt:attr name="android:fillColor">\n'
    s += f'{ind}    <gradient android:type="linear" android:startX="{x1:.2f}" android:startY="{y1:.2f}" android:endX="{x2:.2f}" android:endY="{y2:.2f}">\n'
    for o, c in stops:
        s += f'{ind}        <item android:offset="{o:.2f}" android:color="{c}"/>\n'
    return s + f"{ind}    </gradient>\n{ind}</aapt:attr>\n"


def grad_radial(cx, cy, r, stops, ind="        "):
    s = f'{ind}<aapt:attr name="android:fillColor">\n'
    s += f'{ind}    <gradient android:type="radial" android:centerX="{cx:.2f}" android:centerY="{cy:.2f}" android:gradientRadius="{r:.2f}">\n'
    for o, c in stops:
        s += f'{ind}        <item android:offset="{o:.2f}" android:color="{c}"/>\n'
    return s + f"{ind}    </gradient>\n{ind}</aapt:attr>\n"


def argb(hex_rgb, a):
    return "#%02X%s" % (round(a * 255), hex_rgb[1:])


def background_paths(rect_d, k, ox=0.0, oy=0.0, ind="    "):
    """The lit background, in a viewport where 1024 design units are [k] wide, offset by (ox, oy)."""
    X = lambda v: ox + v * k
    Y = lambda v: oy + v * k
    s = f'{ind}<path android:pathData="{rect_d}">\n' + grad_linear(X(120), Y(0), X(900), Y(1024), BG_STOPS, ind + "    ") + f"{ind}</path>\n"
    s += f'{ind}<path android:pathData="{rect_d}">\n' + grad_radial(X(230), Y(150), 760 * k,
        [(0.0, argb("#FFFFFF", 0.30)), (0.6, argb("#FFFFFF", 0.05)), (1.0, argb("#FFFFFF", 0.0))], ind + "    ") + f"{ind}</path>\n"
    s += f'{ind}<path android:pathData="{rect_d}">\n' + grad_radial(X(860), Y(980), 760 * k,
        [(0.0, argb("#020617", 0.38)), (1.0, argb("#020617", 0.0))], ind + "    ") + f"{ind}</path>\n"
    return s


def shadow_path(tx, k, ind="    "):
    """A soft shadow under the disc: a radial gradient (vectors cannot blur)."""
    cx, cy = tx((CX, CY + 24))
    r = (R + 46) * k
    circle = (f"M{cx - r:.2f},{cy:.2f} A{r:.2f},{r:.2f} 0 1,0 {cx + r:.2f},{cy:.2f} "
              f"A{r:.2f},{r:.2f} 0 1,0 {cx - r:.2f},{cy:.2f} Z")
    return (f'{ind}<path android:pathData="{circle}">\n' +
            grad_radial(cx, cy, r, [(0.0, argb(SHADOW, 0.34)), (0.80, argb(SHADOW, 0.30)), (1.0, argb(SHADOW, 0.0))], ind + "    ") +
            f"{ind}</path>\n")


def mark_path(d, tx, ind="    ", name=None, solid=None):
    n = f' android:name="{name}"' if name else ""
    if solid:
        return f'{ind}<path{n} android:fillType="evenOdd" android:fillColor="{solid}" android:pathData="{d}"/>\n'
    (x1, y1), (x2, y2) = tx((CX, CY - R)), tx((CX, CY + R))
    return (f'{ind}<path{n} android:fillType="evenOdd" android:pathData="{d}">\n' +
            grad_linear(x1, y1, x2, y2, [(0.0, MARK_TOP), (1.0, MARK_BOTTOM)], ind + "    ") + f"{ind}</path>\n")


def circle_d(cx, cy, r):
    return (f"M{cx - r:.2f},{cy:.2f} A{r:.2f},{r:.2f} 0 1,0 {cx + r:.2f},{cy:.2f} "
            f"A{r:.2f},{r:.2f} 0 1,0 {cx - r:.2f},{cy:.2f} Z")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print("  " + path)


# --------------------------------------------------------------- assets
def adaptive(m):
    # The adaptive layer is 108dp; launchers show its middle 72dp through
    # their own mask. The master is the icon as seen - 1024 units across the
    # visible 72dp - so the disc keeps its proportion of the tile: R/1024 of
    # 72dp is a 21.1dp radius, well inside the 33dp safe circle.
    k = 72.0 / 1024
    tx = lambda c: (54 + (c[0] - CX) * k, 54 + (c[1] - CY) * k)
    fg = vd_open(108, 108) + shadow_path(tx, k) + mark_path(poly_d(m, tx, 3), tx) + "</vector>\n"
    write(f"{RES}/drawable/ic_launcher_foreground.xml", fg)
    # The background fills the whole layer, its light laid out over the
    # visible 72dp the same way the master lays it over 1024 - so the lit
    # corner is where the mask shows it.
    bg = vd_open(108, 108) + background_paths("M0,0h108v108h-108z", 72.0 / 1024, 18.0, 18.0) + "</vector>\n"
    write(f"{RES}/drawable/ic_launcher_background.xml", bg)
    mono = vd_open(108, 108) + mark_path(poly_d(m, tx, 3), tx, solid="#FFFFFFFF") + "</vector>\n"
    write(f"{RES}/drawable/ic_launcher_monochrome.xml", mono)


def status_icon(m):
    # The status bar and notification icon: white on transparent, filling a
    # 24dp box as Android's guidelines ask (22dp mark, 1dp padding). Named
    # media3_notification_small_icon, it replaces Media3's own note in the
    # playback notification too.
    k = 11.0 / R
    tx = lambda c: (12 + (c[0] - CX) * k, 12 + (c[1] - CY) * k)
    v = vd_open(24, 24) + mark_path(poly_d(m, tx, 3), tx, solid="#FFFFFFFF") + "</vector>\n"
    write(f"{RES}/drawable/ic_stat_via.xml", v)
    write(f"{RES}/drawable/media3_notification_small_icon.xml", v)


def tile(m):
    # The icon itself as a rounded tile, for the Home header and About: the
    # same picture you tap on the home screen.
    k = 64 / 1024
    tx = lambda c: (c[0] * k, c[1] * k)
    r = 64 * 0.27
    rr = (f"M{r:.2f},0 H{64 - r:.2f} A{r:.2f},{r:.2f} 0 0,1 64,{r:.2f} V{64 - r:.2f} "
          f"A{r:.2f},{r:.2f} 0 0,1 {64 - r:.2f},64 H{r:.2f} A{r:.2f},{r:.2f} 0 0,1 0,{64 - r:.2f} "
          f"V{r:.2f} A{r:.2f},{r:.2f} 0 0,1 {r:.2f},0 Z")
    v = (vd_open(64, 64) + '    <clip-path android:pathData="' + rr + '"/>\n' +
         background_paths(rr, k) + shadow_path(tx, k) + mark_path(poly_d(m, tx, 3), tx) + "</vector>\n")
    write(f"{RES}/drawable/via_mark.xml", v)


SPLASH_DISC = 33.0      # dp of the 108dp splash drawable; Android shows the middle 72dp (radius 36)
SPLASH_MARK = 21.0


def splash(open_m, closed_m):
    k = SPLASH_MARK / R
    tx = lambda c: (54 + (c[0] - CX) * k, 54 + (c[1] - CY) * k)
    kd = 2 * SPLASH_DISC / 1024
    disc_d = circle_d(54, 54, SPLASH_DISC)
    disc = background_paths(disc_d, kd, 54 - SPLASH_DISC, 54 - SPLASH_DISC, ind="        ")

    still = (vd_open(108, 108) + background_paths(disc_d, kd, 54 - SPLASH_DISC, 54 - SPLASH_DISC) +
             shadow_path(tx, k) + mark_path(poly_d(open_m, tx, 3), tx) + "</vector>\n")
    write(f"{RES}/drawable/splash_icon.xml", still)

    anchors = rim_anchors(open_m)
    d_closed = morph_d(closed_m, anchors, tx)
    d_open = morph_d(open_m, anchors, tx)
    (x1, y1), (x2, y2) = tx((CX, CY - R)), tx((CX, CY + R))
    av = (HEAD +
          '<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt">\n'
          '    <aapt:attr name="android:drawable">\n'
          '        <vector android:width="108dp" android:height="108dp"\n'
          '            android:viewportWidth="108" android:viewportHeight="108">\n'
          '            <group android:name="disc" android:pivotX="54" android:pivotY="54"\n'
          '                android:scaleX="0.55" android:scaleY="0.55">\n' +
          background_paths(disc_d, kd, 54 - SPLASH_DISC, 54 - SPLASH_DISC, ind="                ") +
          '            </group>\n'
          '            <group android:name="iris" android:pivotX="54" android:pivotY="54"\n'
          '                android:rotation="-100" android:scaleX="0.7" android:scaleY="0.7">\n' +
          shadow_path(tx, k, ind="                ") +
          f'                <path android:name="blades" android:fillType="evenOdd" android:pathData="{d_closed}">\n' +
          grad_linear(x1, y1, x2, y2, [(0.0, MARK_TOP), (1.0, MARK_BOTTOM)], "                    ") +
          '                </path>\n'
          '            </group>\n'
          '        </vector>\n'
          '    </aapt:attr>\n'
          # the lit disc grows in under it
          '    <target android:name="disc">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <set>\n'
          '                <objectAnimator android:propertyName="scaleX" android:valueFrom="0.55" android:valueTo="1"\n'
          '                    android:duration="520" android:interpolator="@android:interpolator/fast_out_slow_in"/>\n'
          '                <objectAnimator android:propertyName="scaleY" android:valueFrom="0.55" android:valueTo="1"\n'
          '                    android:duration="520" android:interpolator="@android:interpolator/fast_out_slow_in"/>\n'
          '            </set>\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          # the iris turns into place
          '    <target android:name="iris">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <set>\n'
          '                <objectAnimator android:propertyName="rotation" android:valueFrom="-100" android:valueTo="0"\n'
          '                    android:duration="820" android:interpolator="@android:interpolator/decelerate_cubic"/>\n'
          '                <objectAnimator android:propertyName="scaleX" android:valueFrom="0.7" android:valueTo="1"\n'
          '                    android:duration="620" android:interpolator="@android:interpolator/fast_out_slow_in"/>\n'
          '                <objectAnimator android:propertyName="scaleY" android:valueFrom="0.7" android:valueTo="1"\n'
          '                    android:duration="620" android:interpolator="@android:interpolator/fast_out_slow_in"/>\n'
          '            </set>\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          # and opens onto play
          '    <target android:name="blades">\n'
          '        <aapt:attr name="android:animation">\n'
          '            <objectAnimator android:propertyName="pathData" android:valueType="pathType"\n'
          '                android:startOffset="180" android:duration="640"\n'
          '                android:interpolator="@android:interpolator/fast_out_slow_in"\n'
          f'                android:valueFrom="{d_closed}"\n'
          f'                android:valueTo="{d_open}"/>\n'
          '        </aapt:attr>\n'
          '    </target>\n'
          '</animated-vector>\n')
    write(f"{RES}/drawable/splash_icon_animated.xml", av)


# ------------------------------------------------------------ the master
def master_svg(m):
    d = poly_d(m, lambda c: c, 2)
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in BG_STOPS)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024"><defs>'
            f'<linearGradient id="bg" x1="120" y1="0" x2="900" y2="1024" gradientUnits="userSpaceOnUse">{stops}</linearGradient>'
            '<radialGradient id="hl" cx="230" cy="150" r="760" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.30"/>'
            '<stop offset="0.6" stop-color="#FFFFFF" stop-opacity="0.05"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>'
            '<radialGradient id="vg" cx="860" cy="980" r="760" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#020617" stop-opacity="0.38"/>'
            '<stop offset="1" stop-color="#020617" stop-opacity="0"/></radialGradient>'
            '<filter id="sh" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="30"/></filter>'
            f'<linearGradient id="w" x1="0" y1="{CY - R}" x2="0" y2="{CY + R}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{MARK_TOP}"/><stop offset="1" stop-color="{MARK_BOTTOM}"/></linearGradient></defs>'
            '<rect width="1024" height="1024" fill="url(#bg)"/><rect width="1024" height="1024" fill="url(#hl)"/>'
            '<rect width="1024" height="1024" fill="url(#vg)"/>'
            f'<path d="{d}" fill="{SHADOW}" fill-opacity="0.34" fill-rule="evenodd" transform="translate(0,24)" filter="url(#sh)"/>'
            f'<path d="{d}" fill="url(#w)" fill-rule="evenodd"/></svg>\n')


def pngs(svg_text):
    try:
        import numpy as np
        import skia
        from PIL import Image, ImageDraw
    except ImportError:
        print("  (skia-python, numpy or pillow missing: PNGs not written)")
        return

    def render(px):
        dom = skia.SVGDOM.MakeFromStream(skia.MemoryStream(svg_text.encode(), True))
        s = skia.Surface(px, px)
        with s as c:
            c.scale(px / 1024, px / 1024)
            dom.render(c)
        return Image.fromarray(np.asarray(s.makeImageSnapshot().toarray(colorType=skia.kRGBA_8888_ColorType))).convert("RGB")

    for name, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
        px = int(48 * scale)
        img = render(px * 4).resize((px, px), Image.LANCZOS)
        os.makedirs(f"{RES}/mipmap-{name}", exist_ok=True)
        img.save(f"{RES}/mipmap-{name}/ic_launcher.png", optimize=True)
        m = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(m).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        rnd = img.convert("RGBA")
        rnd.putalpha(m.resize((px, px), Image.LANCZOS))
        rnd.save(f"{RES}/mipmap-{name}/ic_launcher_round.png", optimize=True)
        print(f"  {RES}/mipmap-{name}/ic_launcher(_round).png")
    os.makedirs("store", exist_ok=True)
    render(2048).resize((512, 512), Image.LANCZOS).save("store/play_icon_512.png", optimize=True)
    print("  store/play_icon_512.png")


def main():
    if not os.path.isdir(RES):
        sys.exit("run from the repository root")
    m = mark()
    closed = mark(CLOSED)
    os.makedirs("art", exist_ok=True)
    svg = master_svg(m)
    write("art/via-icon.svg", svg)
    adaptive(m)
    status_icon(m)
    tile(m)
    splash(m, closed)
    pngs(svg)


if __name__ == "__main__":
    main()
