# -*- coding: utf-8 -*-
"""
Generate Android launcher icon resources from the 5 app-icon masters.

Design geometry measured from the masters (1024x1024 canvas):
  purple plate #333587 : rounded square, corner radius ~224px, speech-bubble tail
  white bubble         : 560x559 (54.7% of canvas), centred at (512, 512)
  dark X #24265E       : 242x242 (23.6% of canvas), centred at (511.5, 468.5)

Key decision -- adaptive icons get an *inset* foreground:
  Android reserves only the middle 72/108 dp (66.7%) of an adaptive canvas as
  the safe zone, and the launcher mask (circle / squircle / rounded square)
  cuts everything outside it. The master's purple plate fills the whole
  canvas, so pasting it into the foreground layer would let the plate's own
  rounded corners poke out of the mask, and using it as a background layer
  paints a rounded square *under* the mask's own rounding -- a "rounded corner
  inside a rounded corner" artefact.

  So we split the artwork back into the layers the designer composed from:
    background  -> flat #333587 drawn edge to edge (the mask shapes it)
    foreground  -> white bubble + dark X, original 54.7% proportion preserved
                   so the icon matches system icons in apparent size
    monochrome  -> the same silhouette in flat black; the system tints it

Two extraction traps this script exists to avoid:

  1. The masters' four corners are *transparent* (the rounded square curves
     away), so alpha is an exact mask and we must never sample a corner for
     the plate colour -- pass the known plate RGB instead.

  2. The X glyph (#24265E) sits only 70 units of Manhattan distance from the
     plate (#333587) -- closer than the antialiased seam between plate and
     bubble is wide. So *any* threshold rule of the form "drop pixels near the
     plate colour" also deletes the X, leaving a blank white bubble. We instead
     flood-fill from the canvas edge, which removes exactly the connected
     plate region and cannot reach the X because the bubble fully encloses it.
"""
import os
from collections import deque
from PIL import Image

SRC = r"C:/Users/wzy/WorkBuddy/2026-09-19-07-25-43/app-icon"
RES = r"D:/vrcx-1/android/app/src/main/res"

CANVAS = 1024
PLATE_DAY = (51, 54, 133)
PLATE_NIGHT = (18, 20, 33)

LEGACY_DP = 48
ADAPTIVE_DP = 108
DENSITIES = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]

written = []


def save(im, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG", optimize=True)
    written.append((path[len(RES) + 1:].replace("\\", "/"), im.size))


def extract_foreground(master_name: str, plate_rgb) -> Image.Image:
    """Bubble + X on transparency, with the plate removed.

    Uses a BFS flood fill seeded from every canvas-edge pixel that is plate
    coloured. The plate is one connected region touching the border; the X is
    sealed inside the bubble and therefore unreachable. This is why a
    threshold-based erase cannot be used -- see the module docstring.
    """
    src = Image.open(os.path.join(SRC, master_name)).convert("RGBA")
    w, h = src.size
    px = src.load()

    pr, pg, pb = plate_rgb
    tol = 34

    def is_plate(x, y):
        r, g, b, a = px[x, y]
        return a > 8 and abs(r - pr) <= tol and abs(g - pg) <= tol and abs(b - pb) <= tol

    removed = bytearray(w * h)
    q = deque()

    # Seed from the border only.
    for x in range(w):
        for y in (0, h - 1):
            if is_plate(x, y) and not removed[y * w + x]:
                removed[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_plate(x, y) and not removed[y * w + x]:
                removed[y * w + x] = 1
                q.append((x, y))

    while q:
        x, y = q.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not removed[i] and is_plate(nx, ny):
                    removed[i] = 1
                    q.append((nx, ny))

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    opx = out.load()
    kept = 0
    for y in range(h):
        row = y * w
        for x in range(w):
            if removed[row + x]:
                continue
            c = px[x, y]
            if c[3] > 8:
                opx[x, y] = c
                kept += 1
    print(f"    flood-fill removed {sum(removed)} px, kept {kept} px")
    return out


def build_monochrome() -> Image.Image:
    """Flat-black silhouette of bubble + X for themed icons (API 33+)."""
    fg = extract_foreground("vrcx0-icon-1024.png", PLATE_DAY)
    px = fg.load()
    out = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    opx = out.load()
    for y in range(CANVAS):
        for x in range(CANVAS):
            if px[x, y][3] > 8:
                opx[x, y] = (0, 0, 0, 255)
    return out


def scaled(im, side):
    return im.copy() if side == CANVAS else im.resize((side, side), Image.LANCZOS)


def full_bleed(master_name, side):
    """Legacy raster: the finished master, plate and all, scaled down."""
    return scaled(Image.open(os.path.join(SRC, master_name)).convert("RGBA"), side)


def dark_round_bleed(side):
    """Re-colour the circular master's plate for night mode.

    There is no `-dark` twin of vrcx0-icon-1024-circle.png in the delivered
    assets, and the square dark master would show its corners on a round-icon
    launcher. So we take the circular master and swap its plate colour for the
    night plate, which keeps the silhouette round and the palette dark.
    """
    im = Image.open(os.path.join(SRC, "vrcx0-icon-1024-circle.png")).convert("RGBA")
    px = im.load()
    w, h = im.size
    pr, pg, pb = PLATE_DAY
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    opx = out.load()
    tol = 30
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 8 and abs(r - pr) <= tol and abs(g - pg) <= tol and abs(b - pb) <= tol:
                opx[x, y] = (PLATE_NIGHT[0], PLATE_NIGHT[1], PLATE_NIGHT[2], a)
            else:
                opx[x, y] = (r, g, b, a)
    return scaled(out, side)


print("--- extracting adaptive foreground layers ---")
print("  day master:")
fg_day = extract_foreground("vrcx0-icon-1024.png", PLATE_DAY)
print("  night master:")
fg_night = extract_foreground("vrcx0-icon-1024-dark.png", PLATE_NIGHT)
print("  monochrome:")
mono = build_monochrome()

print("--- extraction sanity (content bbox; expect ~(232,232,792,792)) ---")
for tag, im in (("foreground(day)", fg_day), ("foreground(night)", fg_night), ("monochrome", mono)):
    print(f"    {tag:18s} {im.getbbox()}")

for name, mult in DENSITIES:
    legacy_px = int(round(LEGACY_DP * mult))
    adaptive_px = int(round(ADAPTIVE_DP * mult))

    # adaptive foreground, at the full 108dp canvas.
    # The night variant must land in a `-night` *qualified directory* under the
    # SAME resource name: a `_night` filename suffix does nothing, because
    # Android switches resources by directory qualifier, not by file name.
    save(scaled(fg_day, adaptive_px), f"mipmap-{name}", "ic_launcher_foreground.png")
    save(scaled(fg_night, adaptive_px), f"mipmap-night-{name}", "ic_launcher_foreground.png")
    save(scaled(mono, adaptive_px), f"mipmap-{name}", "ic_launcher_monochrome.png")

    # legacy raster launcher icons
    save(full_bleed("vrcx0-icon-1024.png", legacy_px), f"mipmap-{name}", "ic_launcher.png")
    save(full_bleed("vrcx0-icon-1024-circle.png", legacy_px), f"mipmap-{name}", "ic_launcher_round.png")
    save(full_bleed("vrcx0-icon-1024-dark.png", legacy_px), f"mipmap-night-{name}", "ic_launcher.png")
    save(dark_round_bleed(legacy_px), f"mipmap-night-{name}", "ic_launcher_round.png")

# --- XML layers -------------------------------------------------------------
# Emitted from the script rather than hand-kept so a regeneration cannot leave
# the adaptive-icon descriptors stale or missing (they live in a mipmap-*
# directory, so a naive "clean every mipmap dir" would otherwise wipe them).
_XML_BODY = """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""

IC_LAUNCHER_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive launcher icon (API 26+).

  Layer split follows the original artwork rather than the flat master: the
  launcher shapes the background itself, and the foreground carries only the
  bubble + X. Pasting the flat master in here instead would place the plate's
  own rounded corners inside the launcher's mask, giving a visible
  "rounded corner inside a rounded corner" seam.

  The monochrome layer is a flat black silhouette; Android 13+ tints it to
  match the user's wallpaper palette, which is why it must not carry colour.
-->
""" + _XML_BODY

IC_LAUNCHER_ROUND_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon for launchers that request a round icon.

  Identical structure to ic_launcher.xml: the round-ness comes from the
  launcher's own mask, not from the artwork. Android picks this file up via
  android:roundIcon in the manifest, and on API 26+ it is the mask rather than
  this drawable that decides whether the result is a circle.
-->
""" + _XML_BODY

for fname, body in (("ic_launcher.xml", IC_LAUNCHER_XML),
                    ("ic_launcher_round.xml", IC_LAUNCHER_ROUND_XML)):
    path = os.path.join(RES, "mipmap-anydpi-v26", fname)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    written.append((f"mipmap-anydpi-v26/{fname}", "xml"))

for p, s in written:
    if s == "xml":
        print(f"       xml  {p}")
    else:
        print(f"  {s[0]:>4}x{s[1]:<4}  {p}")
print("total", len(written))
