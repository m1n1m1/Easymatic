# -*- coding: utf-8 -*-
"""Every icon Easymatic ships, from one description of the mark.

Run from anywhere:

    python art/generate-icons.py

### Why a script rather than a drawing

The mark has to exist in seven incompatible formats: an SVG master, three Android
vector drawables for the launcher and one for the status bar, five densities of legacy
WebP, a 512 PNG for Play, an SVG favicon and an ICO one. Hand-cut, those drift -- the SVG gains a rounder corner and the launcher
keeps the old one, and nobody notices until the two are seen side by side. So the
geometry is written once, here, and everything else is output. `art/icon.svg` is
*generated*, not authored; edit this file instead.

### The mark

A single node card, the way one sits on the editor canvas, with its output socket
bulging out of the right edge. The silhouette is the whole idea: a rounded square is
every app, a rounded square with a socket on it is this one. That is what has to
survive 32 pixels, and it is why the socket is a bump in the *outline* rather than a
dot painted on the card.

  * The card is authored 260 square at (100,126), radius 62, on a 512 canvas, and
    every length below is then scaled by MARK_SCALE about the centre (see "The one
    number worth understanding"). The socket is a circle of radius 56 centred on the
    card's right edge at y=256, so it sits between the corner arcs (which end at y=188
    and start at y=324) and the union is one clean outline with no overlap to hide.
  * The socket is drawn as a cream ring around a dark centre, not a flat dot. A flat
    dot on an orange bump smears to nothing at 32px; a hole in the shape survives any
    amount of blur.
  * Three cream bars inside are what is left of the letter E. They are an interior
    detail, not the silhouette, and the monochrome layer punches them out as holes.
  * The card is lifted: a darker copy offset by (8,12) is its extrusion, a translucent
    white line under the top edge is its light, and a soft orange glow sits behind it
    on every *opaque* output. The socket stays flat so it reads as a hole in the
    surface rather than as a second object.
  * The mark's ink is INK_BOX (the extrusion included): authored x 100..416,
    y 126..398, scaled to 69..448 by 100..426. That is what the favicon and the
    wordmark crop to, so neither of them moves when MARK_SCALE does.

### Where a ground is drawn, and where it is not

GROUND -- a warm radial dark -- is composited only where an opaque, full-bleed image
is required: the Play store icon, the adaptive icon's *background* layer, the legacy
mipmaps, and the feature graphic. The glow goes with it, because a glow on a
transparent ground is invisible. The SVG master, the adaptive *foreground*, the
monochrome layer, the status-bar icon and both favicons stay transparent, because
transparency is what lets a launcher mask work.

### Two places Android keeps only the alpha

A themed launcher icon (Android 13, "Themed icons" in the launcher) and a notification's
small icon -- the status bar, the always-on display, the shade -- are both drawn by
tinting one colour through the drawable's alpha. Neither can be the coloured mark: a
gradient card with cream bars on it flattens into a single blob, which is what
`R.mipmap.ic_launcher` as a small icon looked like. Both therefore share one geometry,
the silhouette with its bars and socket ring punched out as holes (`monochrome_data`).
They differ only in framing: the themed layer sits in the adaptive 108dp canvas at
SAFE_SCALE like the foreground, the status icon fills its 24dp canvas edge to edge
across, which puts the card at the 20dp a status glyph stands, because a launcher
shrinks its layer and the status bar does not.

### The two numbers worth understanding

SAFE_SCALE shrinks the mark inside the adaptive layers. An adaptive icon is 108dp, of
which a launcher shows the inner 72dp and guarantees only a 66dp circle. The 512
canvas here *is* the 72dp visible area -- every raster output treats it that way --
so the vector layers scale it by exactly 72/108 about the centre, and the adaptive
icon shows the same proportions as the Play icon and the mipmaps.

MARK_SCALE decides how much of that canvas the mark fills, and it is the one to
touch if the icon looks small or crowded. Play draws a square mark on a 384 keyline,
75% of the 512; the mark as authored was 316 wide, 62%, with air around it that no
mask asked for. At 1.2 the card is 379 wide, on the keyline, and the farthest point of
the mark -- the extruded bottom-left corner -- is 30dp from the centre, inside the 33dp
the strictest launcher mask keeps. 1.3 would put it on the mask; do not go past 1.25.
"""

import math
import os
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CANVAS = 512
SAFE_SCALE = 72 / 108

ORANGE = "#ff6a2b"          # website --signal: the flat brand colour, used where there is no gradient
ORANGE_TOP = "#ff8f52"      # the card's gradient, top ...
ORANGE_BOTTOM = "#f2571a"   # ... and bottom
SHADE = "#8a2f0d"           # the extrusion under the card
CREAM = "#fbf6ee"           # bars and socket ring
INK = "#171310"             # the socket's centre
GROUND_CENTRE = "#2b2420"   # the opaque ground's radial gradient ...
GROUND_EDGE = "#0d0b0a"     # ... and its edge (the website's --surface, near enough)
HIGHLIGHT_ALPHA = 0.32
GLOW_ALPHA = 0.20

# The mark is authored on the numbers in the module docstring and then scaled by
# MARK_SCALE about the canvas centre, so one number decides how much of the 512 it
# fills. 1.2 puts the card on Play's 384 keyline (75% of the canvas, where the
# authored 316 sat at 62%) and keeps the adaptive layers 3dp inside the mask.
MARK_SCALE = 1.2


def _len(v):
    """An authored length, scaled."""
    return round(v * MARK_SCALE)


def _pt(x, y):
    """An authored point, scaled about the canvas centre."""
    c = CANVAS / 2
    return round(c + (x - c) * MARK_SCALE), round(c + (y - c) * MARK_SCALE)


CARD = _pt(100, 126) + (_len(260), _len(260))
RADIUS = _len(62)
# On the card's right edge, halfway down: derived, so rounding cannot pull it off.
SOCKET = (CARD[0] + CARD[2], CARD[1] + CARD[3] // 2)
SOCKET_R = _len(56)
RING_R = _len(30)
PIN_R = _len(16)
LIFT = (_len(8), _len(12))
BARS = [_pt(x, y) + (_len(w), _len(h))
        for x, y, w, h in [(160, 184, 126, 34), (160, 239, 90, 34), (160, 294, 126, 34)]]
BAR_RADIUS = _len(17)
HIGHLIGHT = (_pt(162, 133), _pt(298, 133))
HIGHLIGHT_WIDTH = _len(10)
GLOW = (256, 270, 200, 190)  # centre and radii of the ellipse behind the card
GLOW_BLUR = 36
GROUND_RADIAL = (0.5, 0.32, 0.8)  # centre (fractions of the canvas) and radius

INK_BOX = (CARD[0], CARD[1], SOCKET[0] + SOCKET_R, CARD[1] + CARD[3] + LIFT[1])


# --------------------------------------------------------------------------- paths

def outline(dx=0, dy=0):
    """The card and its socket as one closed path.

    Clockwise from the top-left corner: top edge, top-right corner, down to the
    socket, the socket's arc (large, sweeping right), down to the bottom-right corner,
    and back round. The socket's chord runs from y=200 to y=312 on the right edge,
    clear of both corner arcs, so nothing overlaps and the union is exact.
    """
    x, y, w, h = CARD
    x, y = x + dx, y + dy
    r = RADIUS
    sx, sy = SOCKET[0] + dx, SOCKET[1] + dy
    sr = SOCKET_R
    return (
        "M{} {} H{} A{} {} 0 0 1 {} {} V{} A{} {} 0 1 1 {} {} V{} A{} {} 0 0 1 {} {} "
        "H{} A{} {} 0 0 1 {} {} V{} A{} {} 0 0 1 {} {} Z"
    ).format(
        x + r, y, x + w - r, r, r, x + w, y + r,
        sy - sr, sr, sr, sx, sy + sr,
        y + h - r, r, r, x + w - r, y + h,
        x + r, r, r, x, y + h - r,
        y + r, r, r, x + r, y,
    )


def _rounded_path(x, y, w, h, r):
    """A rounded rectangle as path data, for the drawables that have no <rect>."""
    return (
        "M{} {} H{} A{} {} 0 0 1 {} {} V{} A{} {} 0 0 1 {} {} "
        "H{} A{} {} 0 0 1 {} {} V{} A{} {} 0 0 1 {} {} Z"
    ).format(
        x + r, y, x + w - r, r, r, x + w, y + r,
        y + h - r, r, r, x + w - r, y + h,
        x + r, r, r, x, y + h - r,
        y + r, r, r, x + r, y,
    )


def _circle_path(cx, cy, r):
    return "M{} {} A{} {} 0 1 0 {} {} A{} {} 0 1 0 {} {} Z".format(
        cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy)


def _highlight_path():
    (x0, y0), (x1, y1) = HIGHLIGHT
    return "M{} {} H{}".format(x0, y0, x1)


def _alpha_hex(alpha):
    return "{:02x}".format(round(alpha * 255))


# --------------------------------------------------------------------------- raster

def _rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _vertical_gradient(width, height, top, bottom):
    """An RGBA image fading `top` to `bottom` down its height."""
    img = Image.new("RGBA", (width, height))
    d = ImageDraw.Draw(img)
    top, bottom = _rgb(top), _rgb(bottom)
    for y in range(height):
        d.line([(0, y), (width, y)], fill=_lerp(top, bottom, y / max(1, height - 1)) + (255,))
    return img


def _radial(size, centre, radius, stops):
    """A square RGBA radial gradient.

    `centre` and `radius` are fractions of `size`; `stops` is a list of
    (offset, (r, g, b, a)). Computed at 256 and resized, because a per-pixel loop at
    2048 is slow and a gradient this soft cannot tell the difference.
    """
    n = 256
    cx, cy = centre[0] * n, centre[1] * n
    rad = radius * n
    data = []
    for y in range(n):
        for x in range(n):
            t = min(1.0, math.hypot(x - cx, y - cy) / rad)
            for i in range(1, len(stops)):
                if t <= stops[i][0]:
                    o0, c0 = stops[i - 1]
                    o1, c1 = stops[i]
                    u = 0 if o1 == o0 else (t - o0) / (o1 - o0)
                    data.append(tuple(round(c0[k] + (c1[k] - c0[k]) * u) for k in range(4)))
                    break
            else:
                data.append(stops[-1][1])
    img = Image.new("RGBA", (n, n))
    img.putdata(data)
    return img.resize((size, size), Image.BICUBIC)


def _silhouette(scale, dx=0, dy=0):
    """The outline as an 8-bit mask at `scale` times the canvas."""
    s = scale
    n = round(CANVAS * s)
    mask = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(mask)
    x, y, w, h = CARD
    d.rounded_rectangle([(x + dx) * s, (y + dy) * s, (x + dx + w) * s, (y + dy + h) * s],
                        radius=RADIUS * s, fill=255)
    cx, cy = SOCKET[0] + dx, SOCKET[1] + dy
    r = SOCKET_R
    d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=255)
    return mask


def render(size, supersample=4):
    """The mark at `size`, on transparent: extrusion, gradient card, highlight, bars
    and socket. No glow -- that belongs to the opaque outputs. Supersampled, because
    Pillow does not antialias shape edges and a 48px icon drawn directly is ragged."""
    s = supersample
    n = CANVAS * s
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    shade = Image.new("RGBA", (n, n), _rgb(SHADE) + (255,))
    img.paste(shade, (0, 0), _silhouette(s, *LIFT))

    x, y, w, h = CARD
    grad = _vertical_gradient(n, h * s, ORANGE_TOP, ORANGE_BOTTOM)
    card = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    card.paste(grad, (0, y * s))
    # The socket bulges past the card's bottom edge by nothing (it is centred), but
    # extend the gradient a little either way so the mask never samples transparent.
    card.paste(grad.crop((0, 0, n, 1)).resize((n, y * s)), (0, 0))
    card.paste(grad.crop((0, h * s - 1, n, h * s)).resize((n, n - (y + h) * s)), (0, (y + h) * s))
    img.paste(card, (0, 0), _silhouette(s))

    d = ImageDraw.Draw(img, "RGBA")
    (hx0, hy0), (hx1, hy1) = HIGHLIGHT
    hw = HIGHLIGHT_WIDTH * s
    light = (255, 255, 255, round(HIGHLIGHT_ALPHA * 255))
    layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.line([(hx0 * s, hy0 * s), (hx1 * s, hy1 * s)], fill=light, width=hw)
    for cx, cy in HIGHLIGHT:
        ld.ellipse([cx * s - hw / 2, cy * s - hw / 2, cx * s + hw / 2, cy * s + hw / 2], fill=light)
    img.alpha_composite(layer)

    for bx, by, bw, bh in BARS:
        d.rounded_rectangle([bx * s, by * s, (bx + bw) * s, (by + bh) * s],
                            radius=BAR_RADIUS * s, fill=CREAM)
    cx, cy = SOCKET
    d.ellipse([(cx - RING_R) * s, (cy - RING_R) * s, (cx + RING_R) * s, (cy + RING_R) * s], fill=CREAM)
    d.ellipse([(cx - PIN_R) * s, (cy - PIN_R) * s, (cx + PIN_R) * s, (cy + PIN_R) * s], fill=INK)

    return img.resize((size, size), Image.LANCZOS)


def glow(size):
    """The soft orange glow behind the card, on transparent, at `size`."""
    cx, cy, rx, ry = GLOW
    layer = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([cx - rx, cy - ry, cx + rx, cy + ry],
                                  fill=_rgb(ORANGE) + (round(GLOW_ALPHA * 255),))
    layer = layer.filter(ImageFilter.GaussianBlur(GLOW_BLUR))
    return layer.resize((size, size), Image.LANCZOS)


def ground(size):
    """The opaque radial ground at `size`."""
    cx, cy, r = GROUND_RADIAL
    return _radial(size, (cx, cy), r, [(0.0, _rgb(GROUND_CENTRE) + (255,)),
                                       (1.0, _rgb(GROUND_EDGE) + (255,))])


def on_ground(size):
    """The mark over the opaque ground, glow included."""
    out = ground(size)
    out.alpha_composite(glow(size))
    out.alpha_composite(render(size))
    return out


def on_disc(size):
    """The mark over an opaque circle -- the legacy round launcher icon."""
    s = 4
    mask = Image.new("L", (size * s, size * s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * s - 1, size * s - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(on_ground(size), (0, 0), mask)
    return out


def ink_only(size):
    """The mark cropped to its ink, with a little air, for the favicon."""
    pad = 0.06
    x0, y0, x1, y1 = INK_BOX
    w, h = x1 - x0, y1 - y0
    side = max(w, h) * (1 + 2 * pad)
    full = round(size * CANVAS / side)
    mark = render(full)
    ox = round((x0 - (side - w) / 2) * full / CANVAS)
    oy = round((y0 - (side - h) / 2) * full / CANVAS)
    return mark.crop((ox, oy, ox + size, oy + size))


# --------------------------------------------------------------------------- vector

def _svg_body(indent):
    """The mark as SVG elements, shared by the master and the favicon."""
    p = indent
    (hx0, hy0), (hx1, hy1) = HIGHLIGHT
    lines = [
        p + '<defs>',
        p + '    <linearGradient id="card" x1="0" y1="{}" x2="0" y2="{}" gradientUnits="userSpaceOnUse">'
        .format(CARD[1], CARD[1] + CARD[3]),
        p + '        <stop offset="0" stop-color="{}"/>'.format(ORANGE_TOP),
        p + '        <stop offset="1" stop-color="{}"/>'.format(ORANGE_BOTTOM),
        p + '    </linearGradient>',
        p + '</defs>',
        '',
        p + '<g id="mark">',
        p + '    <path d="{}" fill="{}"/>'.format(outline(*LIFT), SHADE),
        p + '    <path d="{}" fill="url(#card)"/>'.format(outline()),
        p + '    <path d="{}" stroke="#ffffff" stroke-opacity="{}" stroke-width="{}" stroke-linecap="round" fill="none"/>'
        .format(_highlight_path(), HIGHLIGHT_ALPHA, HIGHLIGHT_WIDTH),
        p + '    <g fill="{}">'.format(CREAM),
    ]
    for x, y, w, h in BARS:
        lines.append(p + '        <rect x="{}" y="{}" width="{}" height="{}" rx="{}"/>'
                     .format(x, y, w, h, BAR_RADIUS))
    lines += [
        p + '        <circle cx="{}" cy="{}" r="{}"/>'.format(SOCKET[0], SOCKET[1], RING_R),
        p + '    </g>',
        p + '    <circle cx="{}" cy="{}" r="{}" fill="{}"/>'.format(SOCKET[0], SOCKET[1], PIN_R, INK),
        p + '</g>',
    ]
    return lines


def write_svg(path, viewbox=None):
    """The transparent master. A `viewbox` crops it -- the favicon wants the ink, not
    the canvas, because a browser tab is 16px and the canvas's air would be a third of it."""
    if viewbox is None:
        vb = (0, 0, CANVAS, CANVAS)
    else:
        vb = viewbox
    lines = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{} {} {} {}"'.format(*vb)
        + ' width="512" height="512" role="img" aria-labelledby="title desc">',
        '    <title id="title">Easymatic</title>',
        '    <desc id="desc">A node card with its output socket on the right edge.</desc>',
        '',
        '    <!-- GENERATED by art/generate-icons.py. Do not edit; edit that script. -->',
        '',
    ] + _svg_body('    ') + ['</svg>', '']
    _write(path, "\n".join(lines))


def _favicon_viewbox():
    pad = 0.06
    x0, y0, x1, y1 = INK_BOX
    w, h = x1 - x0, y1 - y0
    side = max(w, h) * (1 + 2 * pad)
    return (round(x0 - (side - w) / 2), round(y0 - (side - h) / 2), round(side), round(side))


VECTOR_HEAD = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!--',
    '    GENERATED by art/generate-icons.py. Do not edit; edit that script.',
    '',
]


def _vector_open(comment, viewport, aapt=False):
    lines = list(VECTOR_HEAD)
    lines += ['    ' + line if line else '' for line in comment]
    lines += [
        '-->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
        + ('\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ''),
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="{}"'.format(viewport),
        '    android:viewportHeight="{}">'.format(viewport),
    ]
    return lines


def _scaled_group_open():
    return [
        '    <group',
        '        android:pivotX="256"',
        '        android:pivotY="256"',
        '        android:scaleX="{:.6f}"'.format(SAFE_SCALE),
        '        android:scaleY="{:.6f}">'.format(SAFE_SCALE),
    ]


def write_foreground(path):
    comment = [
        'The viewport is the master\'s 512 rather than 108, so the path data below is',
        'the same numbers as art/icon.svg and the two can be compared line for line.',
        'The group scales the mark by 72/108 about the centre: the 512 canvas is the',
        '72dp a launcher shows, so this layer matches the Play icon exactly and the',
        'mark\'s farthest point stays inside the 66dp circle every mask keeps.',
        '',
        'No glow here. It lives in the background layer, because a launcher may',
        'parallax the two layers against each other and a glow that moves with the',
        'card would read as a shadow.',
    ]
    lines = _vector_open(comment, CANVAS, aapt=True) + _scaled_group_open()
    lines += [
        '        <path',
        '            android:pathData="{}"'.format(outline(*LIFT)),
        '            android:fillColor="{}" />'.format(SHADE),
        '        <path',
        '            android:pathData="{}">'.format(outline()),
        '            <aapt:attr name="android:fillColor">',
        '                <gradient',
        '                    android:type="linear"',
        '                    android:startX="256"',
        '                    android:startY="{}"'.format(CARD[1]),
        '                    android:endX="256"',
        '                    android:endY="{}"'.format(CARD[1] + CARD[3]),
        '                    android:startColor="{}"'.format(ORANGE_TOP),
        '                    android:endColor="{}" />'.format(ORANGE_BOTTOM),
        '            </aapt:attr>',
        '        </path>',
        '        <path',
        '            android:pathData="{}"'.format(_highlight_path()),
        '            android:strokeColor="#{}ffffff"'.format(_alpha_hex(HIGHLIGHT_ALPHA)),
        '            android:strokeWidth="{}"'.format(HIGHLIGHT_WIDTH),
        '            android:strokeLineCap="round" />',
    ]
    for x, y, w, h in BARS:
        lines += [
            '        <path',
            '            android:pathData="{}"'.format(_rounded_path(x, y, w, h, BAR_RADIUS)),
            '            android:fillColor="{}" />'.format(CREAM),
        ]
    lines += [
        '        <path',
        '            android:pathData="{}"'.format(_circle_path(SOCKET[0], SOCKET[1], RING_R)),
        '            android:fillColor="{}" />'.format(CREAM),
        '        <path',
        '            android:pathData="{}"'.format(_circle_path(SOCKET[0], SOCKET[1], PIN_R)),
        '            android:fillColor="{}" />'.format(INK),
        '    </group>',
        '</vector>',
        '',
    ]
    _write(path, "\n".join(lines))


def monochrome_data():
    """The mark as one evenOdd path: the silhouette, with the bars and the socket ring
    punched out as holes and the socket's centre filled back in. No extrusion and no
    highlight: both are light, and a tinted icon has none."""
    return " ".join([outline()] + [_rounded_path(x, y, w, h, BAR_RADIUS) for x, y, w, h in BARS]
                    + [_circle_path(SOCKET[0], SOCKET[1], RING_R),
                       _circle_path(SOCKET[0], SOCKET[1], PIN_R)])


def write_monochrome(path):
    comment = [
        'The themed-icon layer: Android 13 tints this one colour and keeps only its',
        'alpha, so it cannot be the foreground: a gradient card with cream bars on it',
        'tints into a single blob. This is the silhouette with the bars and the socket',
        'ring punched out as holes (evenOdd), and the socket\'s centre filled back in.',
        'No extrusion and no highlight: both are light, and a themed icon has none.',
        'Same framing as the foreground, so the launcher shows the two at one size.',
    ]
    lines = _vector_open(comment, CANVAS) + _scaled_group_open() + [
        '        <path',
        '            android:pathData="{}"'.format(monochrome_data()),
        '            android:fillType="evenOdd"',
        '            android:fillColor="{}" />'.format(INK),
        '    </group>',
        '</vector>',
        '',
    ]
    _write(path, "\n".join(lines))


STATUS_DP = 24
STATUS_INSET_DP = 0  # see write_status_icon: the mark is wide, so the inset is on the height


def write_status_icon(path):
    """The notification small icon: the same alpha-only geometry as the themed layer,
    framed for a 24dp status icon rather than a 108dp launcher layer."""
    comment = [
        'The notification small icon, for the status bar, the always-on display and',
        'the shade. Android keeps only the alpha and tints it, exactly as a themed',
        'launcher icon is drawn, so this is the monochrome layer\'s geometry framed',
        'for a status icon: the mark\'s ink box scaled to fill 24dp minus the 2dp',
        'inset every system status icon keeps. White, so a preview without a tint',
        'still reads on the dark surfaces this icon is shown on.',
    ]
    # The mark without its extrusion: the outline's box, not INK_BOX.
    x0, y0 = CARD[0], CARD[1]
    x1, y1 = SOCKET[0] + SOCKET_R, CARD[1] + CARD[3]
    w, h = x1 - x0, y1 - y0
    usable = CANVAS * (STATUS_DP - 2 * STATUS_INSET_DP) / STATUS_DP
    scale = usable / max(w, h)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    lines = list(VECTOR_HEAD)
    lines += ['    ' + line if line else '' for line in comment]
    lines += [
        '-->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="{}dp"'.format(STATUS_DP),
        '    android:height="{}dp"'.format(STATUS_DP),
        '    android:viewportWidth="{}"'.format(CANVAS),
        '    android:viewportHeight="{}">'.format(CANVAS),
        '    <group',
        '        android:pivotX="{:g}"'.format(cx),
        '        android:pivotY="{:g}"'.format(cy),
        '        android:scaleX="{:.6f}"'.format(scale),
        '        android:scaleY="{:.6f}"'.format(scale),
        '        android:translateX="{:g}"'.format(CANVAS / 2 - cx),
        '        android:translateY="{:g}">'.format(CANVAS / 2 - cy),
        '        <path',
        '            android:pathData="{}"'.format(monochrome_data()),
        '            android:fillType="evenOdd"',
        '            android:fillColor="#ffffff" />',
        '    </group>',
        '</vector>',
        '',
    ]
    _write(path, "\n".join(lines))


def write_background(path):
    """The opaque ground and the glow, in the 108 viewport.

    It must cover the whole 108dp: the launcher mask crops this layer, so anything
    transparent here becomes a hole in the icon. The glow's geometry is the master's
    scaled by 72/108 like the foreground, so the two layers line up at rest.
    """
    k = SAFE_SCALE * 108 / CANVAS
    gcx, gcy, grx, gry = GLOW
    cx = 54 + (gcx - 256) * k
    cy = 54 + (gcy - 256) * k
    r = (grx + GLOW_BLUR) * k
    gr_cx, gr_cy, gr_r = GROUND_RADIAL
    comment = [
        'A radial ground with the card\'s glow over it. It must cover the whole 108dp:',
        'the launcher mask crops this layer, so anything transparent here becomes a',
        'hole in the icon. Android has no blur, so the glow is a radial gradient that',
        'fades to nothing at the radius the master\'s Gaussian blur reaches.',
    ]
    lines = _vector_open(comment, 108, aapt=True) + [
        '    <path',
        '        android:pathData="M0,0 H108 V108 H0 Z">',
        '        <aapt:attr name="android:fillColor">',
        '            <gradient',
        '                android:type="radial"',
        '                android:centerX="{}"'.format(round(gr_cx * 108, 2)),
        '                android:centerY="{}"'.format(round(gr_cy * 108, 2)),
        '                android:gradientRadius="{}"'.format(round(gr_r * 108, 2)),
        '                android:startColor="{}"'.format(GROUND_CENTRE),
        '                android:endColor="{}" />'.format(GROUND_EDGE),
        '        </aapt:attr>',
        '    </path>',
        '    <path',
        '        android:pathData="M0,0 H108 V108 H0 Z">',
        '        <aapt:attr name="android:fillColor">',
        '            <gradient',
        '                android:type="radial"',
        '                android:centerX="{}"'.format(round(cx, 2)),
        '                android:centerY="{}"'.format(round(cy, 2)),
        '                android:gradientRadius="{}">'.format(round(r, 2)),
        '                <item android:offset="0" android:color="#{}{}" />'
        .format(_alpha_hex(GLOW_ALPHA), ORANGE.lstrip('#')),
        '                <item android:offset="0.55" android:color="#{}{}" />'
        .format(_alpha_hex(GLOW_ALPHA * 0.45), ORANGE.lstrip('#')),
        '                <item android:offset="1" android:color="#00{}" />'.format(ORANGE.lstrip('#')),
        '            </gradient>',
        '        </aapt:attr>',
        '    </path>',
        '</vector>',
        '',
    ]
    _write(path, "\n".join(lines))


def write_adaptive(path):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- GENERATED by art/generate-icons.py. Do not edit; edit that script. -->',
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
        '    <background android:drawable="@drawable/ic_launcher_background" />',
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />',
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />',
        '</adaptive-icon>',
        '',
    ]
    _write(path, "\n".join(lines))


# --------------------------------------------------------------------- feature image

FEATURE_TAGLINE = "Your mobile assistant"

# The website's typeface, which ships as woff2 only -- a format Pillow cannot read.
# fontTools decompresses it to a TrueType file in a temp directory, so nothing new is
# committed and the conversion cannot go stale. Everything degrades to a system sans
# if either the package or fontTools is missing, because a missing font should cost a
# slightly wrong wordmark rather than no outputs at all.
ARCHIVO_WOFF2 = os.path.join(
    "website", "node_modules", "@fontsource-variable", "archivo", "files",
    "archivo-latin-wght-normal.woff2",
)
FALLBACK_SANS = {
    800: ["C:/Windows/Fonts/segoeuib.ttf", "arialbd.ttf"],
    400: ["C:/Windows/Fonts/segoeui.ttf", "arial.ttf"],
}

_archivo_path = []


def _archivo_ttf():
    """The Archivo variable font as a TrueType file, or None."""
    if _archivo_path:
        return _archivo_path[0]
    source = os.path.join(ROOT, ARCHIVO_WOFF2)
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        print("   (no fontTools; falling back to a system sans)")
        _archivo_path.append(None)
        return None
    if not os.path.exists(source):
        print("   (no Archivo in website/node_modules; falling back to a system sans)")
        _archivo_path.append(None)
        return None
    out = os.path.join(tempfile.gettempdir(), "easymatic-archivo.ttf")
    font = TTFont(source)
    font.flavor = None
    font.save(out)
    _archivo_path.append(out)
    return out


def _font(weight, size):
    """Archivo at `weight`, or the nearest system sans."""
    path = _archivo_ttf()
    if path:
        font = ImageFont.truetype(path, size)
        try:
            font.set_variation_by_axes([weight])
            return font
        except (OSError, ValueError):
            pass
    for name in FALLBACK_SANS[800 if weight >= 700 else 400]:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def _fit(weight, text, size, width):
    """The largest size at or below `size` whose `text` fits `width`."""
    while size > 8:
        font = _font(weight, size)
        if font.getbbox(text)[2] <= width:
            return font
        size -= 1
    return _font(weight, size)


def write_feature_graphic(path):
    """Play's 1024x500 banner.

    Deliberately plain. Play crops this differently on every surface it appears on, so
    anything near an edge is at risk; the mark and two lines of text sit well inside.
    """
    img = ground(1024).crop((0, 262, 1024, 762))
    size = 300
    glow_layer = glow(size)
    img.alpha_composite(glow_layer, (96, 100))
    img.alpha_composite(render(size), (96, 100))
    img = img.convert("RGB")

    # The text column, and the margin Play's cropping makes non-negotiable. Sizes are
    # fitted rather than chosen, because a fixed one has already failed once here: the
    # first tagline was 572 wide against 476 of room and ran four pixels off the canvas.
    # The fit is what makes changing the words safe rather than a thing to re-measure.
    left, right = 452, 928
    column = right - left
    d = ImageDraw.Draw(img)
    title = _fit(800, "Easymatic", 82, column)
    body = _fit(400, FEATURE_TAGLINE, 44, column)
    d.text((left, 182), "Easymatic", font=title, fill="#dedede")
    d.text((left + 4, 292), FEATURE_TAGLINE, font=body, fill="#a3a3a3")
    _save(img, path)


def wordmark(size=150, fill="#dedede"):
    """The mark beside the name.

    The mark used to stand in for the E. It no longer can -- a card is not a letter --
    so it sits before the whole word, the way most product wordmarks are built. Two
    things keep it from reading as an icon parked beside some text. It is scaled so
    its *card* is the cap height of the word, measured from the font rather than
    assumed, and it sits on the baseline (the extrusion hanging just below it, as a
    descender would). And the gap is 0.22em: wider than a letter-space, narrower than
    a word-space, which is the distance at which a mark belongs to the name.

    It carries the dark ground rather than being transparent, and that is deliberate:
    the bars are a pale cream that vanishes on white, and this file has to survive a
    README read in GitHub's light theme.
    """
    text = "Easymatic"
    font = _font(800, size)
    ascent, _ = font.getmetrics()

    cap = font.getbbox("E")
    cap_height = cap[3] - cap[1]

    # The card is the cap height; the socket and the extrusion hang off it.
    scale = cap_height / CARD[3]
    x0, y0, x1, y1 = INK_BOX
    glyph_w = round((x1 - x0) * scale)
    glyph_h = round((y1 - y0) * scale)

    bounds = font.getbbox(text)
    pad = round(size * 0.30)
    gap = round(size * 0.22)
    baseline = pad + cap_height
    below = max(0, bounds[3] - ascent, glyph_h - cap_height)

    width = pad + glyph_w + gap + (bounds[2] - bounds[0]) + pad
    height = baseline + below + pad
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))

    full = round(CANVAS * scale)
    mark = render(full)
    mark = mark.crop((round(x0 * scale), round(y0 * scale),
                      round(x0 * scale) + glyph_w, round(y0 * scale) + glyph_h))
    img.paste(mark, (pad, pad), mark)

    ImageDraw.Draw(img).text((pad + glyph_w + gap - bounds[0], baseline),
                             text, font=font, fill=fill, anchor="ls")
    return img


def write_wordmark(path):
    mark = wordmark(150)
    out = Image.new("RGB", mark.size, GROUND_EDGE)
    out.paste(mark, (0, 0), mark)
    _save(out, path)


def write_social(path):
    """The 1200x630 card a link preview and GitHub's repository header both use.

    One image for both because the two want the same thing and the awkward sizes are
    compatible: GitHub asks for 1280x640 and scales, Open Graph wants 1200x630, and a
    composition centred with this much air survives either crop.
    """
    img = ground(1200).crop((0, 285, 1200, 915))
    # 140 is the largest size at which the whole word plus the mark keeps 100px of
    # air either side; the old wordmark was a letter shorter and ran at 190.
    mark = wordmark(140)
    # 165 rather than a centred paste: the wordmark image carries padding of its own
    # and a descender's worth of room below the baseline, so centring the *box* leaves
    # the ink sitting low. These two numbers centre what is actually visible.
    img.alpha_composite(mark, ((1200 - mark.width) // 2, 165))
    img = img.convert("RGB")

    tagline = _fit(400, FEATURE_TAGLINE, 46, 900)
    d = ImageDraw.Draw(img)
    d.text((600, 378), FEATURE_TAGLINE, font=tagline, fill="#a3a3a3", anchor="ma")
    _save(img, path)


# --------------------------------------------------------------------------- driver

def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("   " + os.path.relpath(path, ROOT))


def _save(img, path, **kw):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, **kw)
    print("   " + os.path.relpath(path, ROOT))


def main():
    def at(*parts):
        return os.path.join(ROOT, *parts)

    print("vector:")
    write_svg(at("art", "icon.svg"))
    write_svg(at("website", "public", "favicon.svg"), viewbox=_favicon_viewbox())
    write_foreground(at("app", "src", "main", "res", "drawable", "ic_launcher_foreground.xml"))
    write_monochrome(at("app", "src", "main", "res", "drawable", "ic_launcher_monochrome.xml"))
    write_background(at("app", "src", "main", "res", "drawable", "ic_launcher_background.xml"))
    write_status_icon(at("app", "src", "main", "res", "drawable", "ic_stat_easymatic.xml"))
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        write_adaptive(at("app", "src", "main", "res", "mipmap-anydpi", name))

    print("launcher mipmaps:")
    # mdpi is the 48dp baseline; the rest are the standard density multipliers.
    for bucket, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)]:
        folder = at("app", "src", "main", "res", "mipmap-" + bucket)
        _save(on_ground(size), os.path.join(folder, "ic_launcher.webp"), lossless=True)
        _save(on_disc(size), os.path.join(folder, "ic_launcher_round.webp"), lossless=True)

    print("favicon:")
    # The ICO is the fallback for browsers that ignore an SVG favicon; the same crop.
    ico = ink_only(48)
    _save(ico, at("website", "public", "favicon.ico"),
          sizes=[(16, 16), (32, 32), (48, 48)])

    print("store:")
    _save(on_ground(512).convert("RGB"),
          at("fastlane", "metadata", "android", "en-US", "images", "icon.png"))
    write_feature_graphic(at("fastlane", "metadata", "android", "en-US",
                             "images", "featureGraphic.png"))

    print("wordmark:")
    write_wordmark(at("art", "wordmark.png"))
    write_social(at("art", "social.png"))
    write_social(at("website", "public", "og.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
