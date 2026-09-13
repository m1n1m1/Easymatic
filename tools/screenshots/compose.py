"""Turns the raw emulator captures into the pictures the website and the store use.

    python tools/screenshots/compose.py [raw_dir]

Three outputs from one 1080x2400 capture per graph:

- ``website/public/img/hero-editor.webp`` — the capture inside a drawn phone:
  bezel, rounded screen, side buttons, transparent around it. 1000 px wide.
- ``website/public/img/showcase/<graph>.webp`` — the capture with the screen's
  own rounded corners and nothing else, 900x2000, so the use-case pane is a
  phone screen rather than a crop with an aspect ratio no phone has.
- ``fastlane/metadata/android/en-US/images/phoneScreenshots/<n>.png`` — 9:16
  store pictures: the framed phone on the site's ground with one line of copy
  above it. Play refuses anything taller than 2:1, which a 9:20 phone capture
  is, so the frame goes onto a 1080x1920 canvas instead of being uploaded raw.

Everything is drawn at 4x and downsampled, so the bezel's curves are smooth.
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SITE_IMG = os.path.join(ROOT, "website", "public", "img")
STORE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "phoneScreenshots")

SCREEN_RADIUS = 96      # px on the 1080-wide capture
BEZEL = 34
BEZEL_RADIUS = SCREEN_RADIUS + BEZEL
BEZEL_COLOUR = (16, 17, 20, 255)
BEZEL_EDGE = (58, 60, 68, 255)
BUTTON_COLOUR = (28, 29, 34, 255)

# The site's own ground and type colours (website/src/styles/global.css).
GROUND = (10, 10, 10)
TEXT = (245, 240, 232)
SIGNAL = (255, 106, 43)

SUPERSAMPLE = 4

# One line per store picture, in the order Play shows them. The hero goes
# first because it is the picture of the app as a whole.
STORE_ORDER = [
    ("hero-editor", "Automate your phone with nodes"),
    ("ai-agent", "Let an AI agent answer for you"),
    ("smart-home", "Run your smart home from a trigger"),
    ("location-time", "Places and times start macros"),
    ("messaging", "Route messages the way you want"),
    ("sensors", "Every sensor is a trigger"),
]


def rounded_screen(capture):
    """The capture with its corners rounded off and everything else transparent."""
    w, h = capture.size
    mask = Image.new("L", (w * SUPERSAMPLE, h * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w * SUPERSAMPLE - 1, h * SUPERSAMPLE - 1), SCREEN_RADIUS * SUPERSAMPLE, fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    out = capture.convert("RGBA")
    out.putalpha(mask)
    return out


def framed_phone(capture):
    """The capture behind a drawn bezel, with the side buttons a phone has."""
    w, h = capture.size
    s = SUPERSAMPLE
    button_reach = 8
    fw, fh = w + 2 * BEZEL + 2 * button_reach, h + 2 * BEZEL
    body = Image.new("RGBA", (fw * s, fh * s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(body)
    x0 = button_reach * s

    def button(y, length):
        # Right-hand edge: power above, volume rocker below. Drawn first so the
        # body covers their inner half.
        draw.rounded_rectangle(
            (x0 + (w + 2 * BEZEL - 2) * s, y * s, x0 + (w + 2 * BEZEL + button_reach - 2) * s, (y + length) * s),
            radius=3 * s, fill=BUTTON_COLOUR)

    button(int(h * 0.19), int(h * 0.055))
    button(int(h * 0.27), int(h * 0.10))

    draw.rounded_rectangle(
        (x0, 0, x0 + (w + 2 * BEZEL) * s - 1, fh * s - 1), BEZEL_RADIUS * s,
        fill=BEZEL_COLOUR, outline=BEZEL_EDGE, width=2 * s)
    body = body.resize((fw, fh), Image.LANCZOS)
    body.alpha_composite(rounded_screen(capture), (button_reach + BEZEL, BEZEL))
    return body


ARCHIVO_WOFF2 = os.path.join(
    ROOT, "website", "node_modules", "@fontsource-variable", "archivo", "files", "archivo-latin-wght-normal.woff2")
ARCHIVO_TTF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts", "Archivo-Bold.ttf")


def archivo_bold():
    """A static bold TTF cut from the site's own variable Archivo, made on demand.

    Pillow cannot read woff2 and the site ships nothing else, so the cut is
    derived here (fontTools plus brotli, both pip-installable) and kept out of
    git. Returns None when the site's dependencies are not installed.
    """
    if os.path.exists(ARCHIVO_TTF):
        return ARCHIVO_TTF
    if not os.path.exists(ARCHIVO_WOFF2):
        return None
    try:
        from fontTools.ttLib import TTFont
        from fontTools.varLib.instancer import instantiateVariableFont
    except ImportError:
        return None
    font = TTFont(ARCHIVO_WOFF2)
    font.flavor = None
    font = instantiateVariableFont(font, {"wght": 700})
    os.makedirs(os.path.dirname(ARCHIVO_TTF), exist_ok=True)
    font.save(ARCHIVO_TTF)
    return ARCHIVO_TTF


def find_font(size):
    candidates = [
        archivo_bold(),
        "C:/Windows/Fonts/segoeuib.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ]
    for path in candidates:
        if path and os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def store_picture(capture, caption):
    """A 1080x1920 store picture: caption on top, framed phone below it."""
    cw, ch = 1080, 1920
    canvas = Image.new("RGB", (cw, ch), GROUND)
    phone = framed_phone(capture)
    # The phone fills the width with a margin and runs off the bottom edge: a
    # phone cut at the foot reads as "there is more", a phone shrunk to fit
    # reads as small.
    target_w = 880
    phone = phone.resize((target_w, int(phone.height * target_w / phone.width)), Image.LANCZOS)
    top = 300
    canvas.paste(phone, ((cw - target_w) // 2, top), phone)

    draw = ImageDraw.Draw(canvas)
    font = find_font(64)
    words = caption.split()
    lines, line = [], ""
    for word in words:
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=font) > cw - 160:
            lines.append(line)
            line = word
        else:
            line = trial
    lines.append(line)
    y = 110
    for text in lines:
        draw.text((cw / 2, y), text, font=font, fill=TEXT, anchor="ma")
        y += 78
    draw.rectangle((cw / 2 - 28, y + 14, cw / 2 + 28, y + 20), fill=SIGNAL)
    return canvas


def main(argv):
    raw = argv[0] if argv else os.path.join(os.path.dirname(__file__), "raw")
    os.makedirs(os.path.join(SITE_IMG, "showcase"), exist_ok=True)
    os.makedirs(STORE, exist_ok=True)

    hero = Image.open(os.path.join(raw, "hero-editor.png")).convert("RGB")
    framed = framed_phone(hero)
    framed = framed.resize((1000, int(framed.height * 1000 / framed.width)), Image.LANCZOS)
    framed.save(os.path.join(SITE_IMG, "hero-editor.webp"), quality=82, method=6)
    print("hero", framed.size)

    for name in ("ai-agent", "messaging", "smart-home", "location-time", "sensors"):
        capture = Image.open(os.path.join(raw, f"{name}.png")).convert("RGB")
        screen = rounded_screen(capture).resize((900, 2000), Image.LANCZOS)
        screen.save(os.path.join(SITE_IMG, "showcase", f"{name}.webp"), quality=82, method=6)
        print("showcase", name, screen.size)

    for index, (name, caption) in enumerate(STORE_ORDER, start=1):
        capture = Image.open(os.path.join(raw, f"{name}.png")).convert("RGB")
        store_picture(capture, caption).save(os.path.join(STORE, f"{index}.png"), optimize=True)
        print("store", index, name)


if __name__ == "__main__":
    main(sys.argv[1:])
