"""Drives an emulator through the editor and captures one PNG per graph.

    python tools/screenshots/capture.py [out_dir] [graph ...]

What it does, in order:

1. Seeds the app's private storage (``run-as``, so the build has to be
   debuggable) with a throwaway AI connection, two hubs and two places, so no
   card wears a Problems badge for pointing at something that is not there.
2. Writes the six workflow files from ``graphs.py`` beside them.
3. Puts the status bar into demo mode — a fixed clock, full battery, Wi-Fi —
   because a store screenshot with 7:32 and a half-empty battery says the
   wrong thing.
4. Restarts the app, taps the macro's row on the list screen, taps the
   fit-to-screen button, and captures the screen at native resolution.

Rows and buttons are found through ``uiautomator dump`` by text and content
description rather than by coordinates, so a layout change moves nothing here.
"""

import os
import re
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(__file__))
import graphs  # noqa: E402

PKG = "io.github.m1n1m1.easymatic"
ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("ANDROID_HOME") or os.path.join(os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk"),
    "platform-tools", "adb.exe" if os.name == "nt" else "adb")
SEED_DIR = os.path.join(os.path.dirname(__file__), "seed")
FIT_BUTTON = "Fit to screen"


def adb(*args, check=True, capture=True):
    result = subprocess.run([ADB, *args], check=check, capture_output=capture, text=True)
    return result.stdout if capture else ""


def shell(*args, **kw):
    return adb("shell", *args, **kw)


def push_private(local, remote):
    """Copies a file into the app's private storage through run-as."""
    tmp = "/data/local/tmp/" + os.path.basename(local)
    adb("push", local, tmp)
    shell("run-as", PKG, "sh", "-c", f"'mkdir -p $(dirname {remote}) && cp {tmp} {remote}'")


def seed():
    push_private(os.path.join(SEED_DIR, "connections.json"), "files/ai/connections.json")
    shell("run-as", PKG, "touch", "files/ai/.profiles")
    push_private(os.path.join(SEED_DIR, "hubs.json"), "files/smarthome/hubs.json")
    push_private(os.path.join(SEED_DIR, "geofences.json"), "files/places/geofences.json")
    with tempfile.TemporaryDirectory() as tmp:
        graphs.write_all(tmp)
        shell("run-as", PKG, "sh", "-c", "'rm -f files/workflows/*.json'")
        for name in os.listdir(tmp):
            push_private(os.path.join(tmp, name), f"files/workflows/{name}")


def demo_status_bar(on=True):
    if not on:
        shell("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
        return
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    demo = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
    shell(*demo, "enter")
    shell(*demo, "clock", "-e", "hhmm", "0900")
    shell(*demo, "battery", "-e", "level", "100", "-e", "plugged", "false")
    shell(*demo, "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    shell(*demo, "network", "-e", "mobile", "hide")
    shell(*demo, "notifications", "-e", "visible", "false")
    shell(*demo, "status", "-e", "bluetooth", "hide", "-e", "alarm", "hide", "-e", "mute", "hide")


def dump():
    return adb("exec-out", "uiautomator", "dump", "/dev/tty")


def find(xml, *, text=None, desc=None):
    """Centre of the first node whose text or content-desc matches."""
    for m in re.finditer(r"<node [^>]*>", xml):
        attrs = m.group(0)
        if text is not None and f'text="{text}"' not in attrs:
            continue
        if desc is not None and f'content-desc="{desc}"' not in attrs:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
        x1, y1, x2, y2 = map(int, b.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def wait_for(*, text=None, desc=None, timeout=15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        hit = find(dump(), text=text, desc=desc)
        if hit:
            return hit
        time.sleep(0.5)
    raise SystemExit(f"never saw text={text!r} desc={desc!r} on screen")


def tap(xy):
    shell("input", "tap", str(xy[0]), str(xy[1]))


def open_macro(name):
    # Without this the first launch after a reboot asks to lift battery
    # restrictions, and the dialog sits on top of the macro list.
    shell("dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    shell("am", "force-stop", PKG)
    shell("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    tap(wait_for(text=name))
    tap(wait_for(desc=FIT_BUTTON))
    time.sleep(1.5)


def screencap(path):
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout
    with open(path, "wb") as f:
        f.write(raw)


def main(argv):
    out = argv[0] if argv else os.path.join(os.path.dirname(__file__), "raw")
    wanted = argv[1:] or list(graphs.GRAPHS)
    os.makedirs(out, exist_ok=True)
    adb("wait-for-device")
    seed()
    demo_status_bar(True)
    try:
        for wid in wanted:
            open_macro(graphs.GRAPHS[wid]["name"])
            screencap(os.path.join(out, f"{wid}.png"))
            print("captured", wid)
    finally:
        demo_status_bar(False)


if __name__ == "__main__":
    main(sys.argv[1:])
