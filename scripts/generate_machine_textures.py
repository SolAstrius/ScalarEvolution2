#!/usr/bin/env python3
"""Procedural placeholder textures for the processing-machine blocks.

Each new block needs a 16×16 (or 32×32) PNG for its cube_all model.
Hand-painted art lands later; for now we draw a simple "metal drum"
that reads as "industrial machine" at MC's tiny scale. Hue per
machine type so they're distinguishable in-world.

Run from repo root:
    nix-shell -p python3Packages.pillow --run \\
        'python3 scripts/generate_machine_textures.py'
"""
from PIL import Image, ImageDraw
from pathlib import Path

TEX_DIR = Path("src/main/resources/assets/scev/textures/block")
SIZE = 16

# (base, dark, light) per machine — picked to read as colored metal.
MACHINES = {
    "pulper":              ((92, 110, 92),   (52, 64, 52),   (140, 162, 140)),  # mossy green — water + plant feedstock
    "sheet_former":        ((130, 130, 140), (78, 78, 88),   (180, 180, 195)),  # cool steel
    "dryer":               ((148, 100, 60),  (88, 56, 32),   (200, 145, 92)),   # warm copper — heat
    "winder":              ((110, 100, 130), (66, 60, 80),   (160, 148, 188)),  # purple-gray — mechanical
    "ink_mixer":           ((40, 40, 55),    (20, 20, 30),   (75, 75, 95)),     # near-black — ink stains
    "ribbon_impregnator":  ((150, 60, 60),   (90, 32, 32),   (200, 95, 95)),    # dark red — ribbon dye
    "teletype":            ((180, 175, 155), (110, 105, 88), (220, 215, 195)),  # cream/beige
}

def draw_drum(base, dark, light):
    """16×16 'metal drum' face: rivets at the corners, horizontal bands
    suggesting a cylindrical shape, slight bevel highlight."""
    img = Image.new("RGBA", (SIZE, SIZE), base + (255,))
    d = ImageDraw.Draw(img)
    # Top/bottom edges — darker bands suggesting a banded drum.
    d.rectangle([0, 0, SIZE - 1, 1], fill=dark + (255,))
    d.rectangle([0, SIZE - 2, SIZE - 1, SIZE - 1], fill=dark + (255,))
    # Mid-height seam.
    d.line([0, SIZE // 2, SIZE - 1, SIZE // 2], fill=dark + (255,))
    # Highlight stripe just above mid (suggests cylinder).
    d.line([1, SIZE // 2 - 2, SIZE - 2, SIZE // 2 - 2], fill=light + (255,))
    # Corner rivets.
    for x, y in [(2, 2), (SIZE - 3, 2), (2, SIZE - 3), (SIZE - 3, SIZE - 3)]:
        d.point((x, y), fill=light + (255,))
        d.point((x + 1, y), fill=dark + (255,))
        d.point((x, y + 1), fill=dark + (255,))
    return img

def main():
    TEX_DIR.mkdir(parents=True, exist_ok=True)
    for name, (base, dark, light) in MACHINES.items():
        out = TEX_DIR / f"{name}.png"
        draw_drum(base, dark, light).save(out)
        print(f"  wrote {out}")

if __name__ == "__main__":
    main()
