#!/usr/bin/env python3
"""Generate terminal-variant textures by recoloring the two main case
colors of vt100.png. The screen, screen reflection, LED, and other
detail pixels stay untouched so all variants read as "the same form
factor in different paint."

The VT100 texture happens to use exactly two case colors (a light cream
and a darker shadow band), discovered via PIL's color count. We map
those two specific RGB values to a per-variant pair and write the
result out under the matching texture name.

Run from the repo root:
    nix-shell -p python3Packages.pillow --run \\
        'python3 scripts/recolor_terminal_textures.py'
"""
from PIL import Image
from pathlib import Path

# vt100.png case colors as discovered by PIL color counts:
SRC_LIGHT = (203, 193, 166, 255)   # case main
SRC_SHADOW = (160, 149, 123, 255)  # case shadow band

# (light, shadow) per variant. Picked to be visually distinct at MC's
# tiny block scale — full-saturation hue shifts wash out, so we go for
# clear lightness / temperature steps instead.
VARIANTS = {
    "vt220": (
        (190, 190, 195, 255),  # cool light gray — DEC's mid-80s palette
        (140, 140, 145, 255),
    ),
    "vt340": (
        (138, 140, 145, 255),  # mid platinum-gray — DEC's late-80s color
        (92, 94, 99, 255),
    ),
    "vt420": (
        (105, 108, 115, 255),  # darker industrial gray — DEC-to-Compaq era
        (68, 71, 78, 255),
    ),
    "vt520": (
        (75, 78, 85, 255),     # charcoal — last DEC, near-black case
        (45, 48, 55, 255),
    ),
}

TEX_DIR = Path("src/main/resources/assets/scev/textures/block")

def recolor(src_path: Path, dst_path: Path, light: tuple, shadow: tuple):
    img = Image.open(src_path).convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            c = px[x, y]
            if c == SRC_LIGHT:
                px[x, y] = light
            elif c == SRC_SHADOW:
                px[x, y] = shadow
    img.save(dst_path)
    print(f"  wrote {dst_path}")

def main():
    src = TEX_DIR / "vt100.png"
    if not src.exists():
        raise SystemExit(f"missing source texture: {src}")
    print(f"recoloring from {src}:")
    for name, (light, shadow) in VARIANTS.items():
        recolor(src, TEX_DIR / f"{name}.png", light, shadow)

if __name__ == "__main__":
    main()
