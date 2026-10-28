#!/usr/bin/env python3
"""Builds Bridgit's adaptive launcher icon layers from one square artwork PNG.

Usage:
    python3 -m venv /tmp/iconvenv && /tmp/iconvenv/bin/pip install pillow numpy
    /tmp/iconvenv/bin/python tools/make-launcher-icon.py <artwork.png> [--preview <dir>]

The artwork is a picture on a flat background colour. This script:
  * samples the background colour from the four corners and writes it to
    app/src/main/res/values/ic_launcher_colors.xml (the adaptive background layer);
  * makes that colour transparent (soft edge, colour un-mixed so there is no dark fringe);
  * scales the remaining artwork so every visible pixel sits inside a circle of
    SAFE_DIAMETER_DP on the 108 dp canvas (the 66 dp safe zone is a circle);
  * writes mipmap-<density>/ic_launcher_foreground.png and ic_launcher_monochrome.png
    (white where the artwork is, transparent elsewhere) at 108/162/216/324/432 px.
With --preview it also writes circle and squircle previews of the composed icon.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
CANVAS_DP = 108.0
SAFE_DIAMETER_DP = 64.0  # a little inside the 66 dp safe circle
MASTER = 1728            # 16 px per dp; downsampled for each density
LO, HI = 14.0, 48.0      # colour distance from the background: <= LO transparent, >= HI opaque


def load(path):
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(np.float64)
    corners = np.concatenate([rgb[:24, :24], rgb[:24, -24:], rgb[-24:, :24], rgb[-24:, -24:]]).reshape(-1, 3)
    bg = np.median(corners, axis=0)
    dist = np.sqrt(((rgb - bg) ** 2).sum(-1))
    alpha = np.clip((dist - LO) / (HI - LO), 0.0, 1.0)
    # Un-mix the background from edge pixels: c = a*fg + (1-a)*bg  =>  fg = (c - (1-a)*bg) / a
    a3 = alpha[..., None]
    fg = np.where(a3 > 0, (rgb - (1 - a3) * bg) / np.maximum(a3, 1e-6), 0)
    fg = np.clip(fg, 0, 255)
    return bg, fg, alpha


def place(fg, alpha):
    """Returns RGBA master canvas with the artwork centred and scaled into the safe circle."""
    ys, xs = np.nonzero(alpha > 0.02)
    # Centre on the smallest enclosing circle's centre, approximated by the bbox centre then refined.
    cx, cy = (xs.min() + xs.max()) / 2.0, (ys.min() + ys.max()) / 2.0
    for _ in range(200):  # simple iterative recentring towards the farthest point
        d = np.hypot(xs - cx, ys - cy)
        i = d.argmax()
        cx += (xs[i] - cx) * 0.01
        cy += (ys[i] - cy) * 0.01
    radius = np.hypot(xs - cx, ys - cy).max()
    target_r = MASTER * (SAFE_DIAMETER_DP / 2.0) / CANVAS_DP
    scale = target_r / radius
    h, w = alpha.shape
    src = Image.fromarray(np.dstack([fg, alpha * 255]).astype(np.uint8), "RGBA")
    small = src.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
    ox = round(MASTER / 2 - cx * scale)
    oy = round(MASTER / 2 - cy * scale)
    # Crop the part of the scaled image that lands on the canvas (all artwork does; only background is cut).
    left, top = max(0, -ox), max(0, -oy)
    small = small.crop((left, top, min(small.width, MASTER - ox), min(small.height, MASTER - oy)))
    canvas = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    canvas.alpha_composite(small, (max(0, ox), max(0, oy)))
    return canvas, radius * scale


def mono(canvas):
    a = canvas.getchannel("A")
    white = Image.new("RGBA", canvas.size, (255, 255, 255, 0))
    white.putalpha(a)
    return white


def preview(bg, fg_master, out_dir):
    size = 432
    fg = fg_master.resize((size, size), Image.LANCZOS)
    tiles = []
    for shape in ("circle", "squircle", "full"):
        base = Image.new("RGBA", (size, size), tuple(int(c) for c in bg) + (255,))
        base.alpha_composite(fg)
        # The launcher shows the middle 72 dp of the 108 dp canvas.
        inset = round(size * 18 / 108)
        view = base.crop((inset, inset, size - inset, size - inset)) if shape != "full" else base
        mask = Image.new("L", view.size, 0)
        dr = ImageDraw.Draw(mask)
        if shape == "circle":
            dr.ellipse((0, 0, view.size[0] - 1, view.size[1] - 1), fill=255)
        elif shape == "squircle":
            dr.rounded_rectangle((0, 0, view.size[0] - 1, view.size[1] - 1), radius=round(view.size[0] * 0.3), fill=255)
        else:
            mask = Image.new("L", view.size, 255)
            # draw the 66 dp safe circle for reference
            r = size * 33 / 108
            c = size / 2
            ImageDraw.Draw(view).ellipse((c - r, c - r, c + r, c + r), outline=(255, 80, 80, 255), width=2)
        tile = Image.new("RGBA", view.size, (240, 240, 240, 255))
        tile.paste(view, (0, 0), mask)
        tiles.append(tile.resize((300, 300), Image.LANCZOS))
    sheet = Image.new("RGBA", (300 * 3 + 40, 340), (240, 240, 240, 255))
    for i, t in enumerate(tiles):
        sheet.paste(t, (10 + i * 310, 20))
    sheet.save(os.path.join(out_dir, "icon_preview.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("artwork")
    ap.add_argument("--preview")
    args = ap.parse_args()
    bg, fg, alpha = load(args.artwork)
    master, r = place(fg, alpha)
    hexbg = "#%02X%02X%02X" % tuple(int(round(c)) for c in bg)
    with open(os.path.join(RES, "values", "ic_launcher_colors.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                "<!-- Adaptive icon background, sampled from the artwork's corners by tools/make-launcher-icon.py. -->\n"
                "<resources>\n"
                f'    <color name="ic_launcher_background">{hexbg}</color>\n'
                "</resources>\n")
    m = mono(master)
    for dens, px in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        m.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)
    print(f"background {hexbg}; artwork radius {r / MASTER * CANVAS_DP:.1f} dp of 33 dp safe radius")
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        preview(bg, master, args.preview)
        print("preview:", os.path.join(args.preview, "icon_preview.png"))


if __name__ == "__main__":
    sys.exit(main())
