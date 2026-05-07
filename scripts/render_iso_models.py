#!/usr/bin/env python3
"""Render scev OBJ models in isometric projection with white outline,
composited onto a background image. Outputs to repo's renders/ directory.

Usage:
    nix-shell -p 'python3.withPackages(ps: with ps; [ trimesh pyrender pillow numpy ])' \\
        --run 'python scripts/render_iso_models.py [model_name ...]'

With no args, renders every entry in OBJ_TO_TEX. NEAREST texture sampling
preserves pixel art; bilinear would smear the 16/32-px Minecraft textures.
"""
import os
os.environ.setdefault("PYOPENGL_PLATFORM", "egl")

import math
import sys
from pathlib import Path

import numpy as np
import trimesh
import pyrender
from pyrender import (
    Mesh,
    Primitive,
    MetallicRoughnessMaterial,
    Texture,
    Sampler,
)
from pyrender.constants import GLTF
from PIL import Image, ImageFilter

REPO = Path("/home/sol/repos/ScalarEvolution2")
MODELS = REPO / "src/main/resources/assets/scev/models/block"
TEXTURES = REPO / "src/main/resources/assets/scev/textures/block"
BG_PATH = Path("/home/sol/Downloads/New_Create_Logo_Background.webp")
OUT_DIR = Path("/home/sol/repos/ScalarEvolution2/renders")
OUT_DIR.mkdir(exist_ok=True)

OBJ_TO_TEX = {
    "crt_monitor": "crt_monitor.png",
    "keyboard": "keyboard.png",
    "keyboard_mouse": "keyboard_mouse.png",
    "powermark": "powermark.png",
    "tinkerpad": "tinkerpad.png",
    "vt100": "vt100.png",
    "workstation": "workstation.png",
}

RES = 2048            # render size (square) — supersample for sharp downscale
OUTPUT_SIZE = 512     # final saved size; Modrinth caps icons at 256 KiB
OUTLINE_PX = 10       # outline thickness in pixels (at RES)
MODEL_MARGIN = 0.78   # >0.5; bigger = more padding around model
FILL_CORNERS = False  # True: bake matching blue into corners (RGB output)
                      # False: leave bg's transparent corners alone (RGBA, theme-adaptive)
ISO_ROT_Y = math.radians(-45)         # right face rotates toward camera
ISO_ROT_X = math.radians(35.264)      # tilt top toward camera


def load_geometry(obj_path: Path) -> trimesh.Trimesh:
    """Load OBJ, dropping the slot-reference MTL trimesh can't resolve."""
    raw = obj_path.read_text().splitlines()
    cleaned = "\n".join(l for l in raw if not l.startswith(("mtllib", "usemtl")))
    mesh = trimesh.load(
        trimesh.util.wrap_as_stream(cleaned), file_type="obj", process=False
    )
    if isinstance(mesh, trimesh.Scene):
        mesh = trimesh.util.concatenate(tuple(mesh.geometry.values()))
    return mesh


def build_pyrender_mesh(mesh: trimesh.Trimesh, tex_path: Path) -> Mesh:
    """Construct a pyrender Mesh with NEAREST-sampled pixel-art texture."""
    img = Image.open(tex_path).convert("RGBA")
    sampler = Sampler(
        magFilter=GLTF.NEAREST,
        minFilter=GLTF.NEAREST,  # also disable mip blur
        wrapS=GLTF.REPEAT,
        wrapT=GLTF.REPEAT,
    )
    texture = Texture(source=img, source_channels="RGBA", sampler=sampler)
    material = MetallicRoughnessMaterial(
        baseColorTexture=texture,
        baseColorFactor=[1.0, 1.0, 1.0, 1.0],
        metallicFactor=0.0,
        roughnessFactor=1.0,
        alphaMode="MASK",
        alphaCutoff=0.5,
        doubleSided=True,
    )
    uv = np.asarray(mesh.visual.uv, dtype=np.float32)
    prim = Primitive(
        positions=np.asarray(mesh.vertices, dtype=np.float32),
        normals=np.asarray(mesh.vertex_normals, dtype=np.float32),
        texcoord_0=uv,
        indices=np.asarray(mesh.faces, dtype=np.uint32).ravel(),
        material=material,
        mode=GLTF.TRIANGLES,
    )
    return Mesh(primitives=[prim])


def build_iso_camera_pose(mesh: trimesh.Trimesh):
    """Isometric view: rotate model -45° Y then -35.264° X (classic), with ortho cam."""
    bounds = mesh.bounds
    center = bounds.mean(axis=0)
    size = (bounds[1] - bounds[0]).max()

    # Camera at +Z looking toward origin after we rotate the mesh into iso pose.
    cam_pose = np.eye(4)
    cam_pose[:3, 3] = [0, 0, size * 3]  # far enough away (ortho ignores distance for projection)
    return center, size, cam_pose


def iso_rotation_matrix() -> np.ndarray:
    # Apply Y-rotation first (turn the box on its vertical axis), then tilt
    # forward around X so we look down onto the top.
    ry = trimesh.transformations.rotation_matrix(ISO_ROT_Y, [0, 1, 0])
    rx = trimesh.transformations.rotation_matrix(ISO_ROT_X, [1, 0, 0])
    return rx @ ry


def render_mesh(mesh: trimesh.Trimesh, tex_path: Path) -> Image.Image:
    center, size, cam_pose = build_iso_camera_pose(mesh)

    # Center the mesh, then apply isometric rotation.
    centered = mesh.copy()
    centered.apply_translation(-center)
    centered.apply_transform(iso_rotation_matrix())

    scene = pyrender.Scene(bg_color=[0, 0, 0, 0], ambient_light=[0.55, 0.55, 0.55])
    scene.add(build_pyrender_mesh(centered, tex_path))

    # Orthographic camera tightly framed around the (rotated) mesh.
    rb = centered.bounds
    extent = max((rb[1] - rb[0])[:2]) * MODEL_MARGIN  # half-extent + margin
    cam = pyrender.OrthographicCamera(xmag=extent, ymag=extent, znear=0.01, zfar=size * 10)
    scene.add(cam, pose=cam_pose)

    # Soft directional light from upper-front-right.
    light = pyrender.DirectionalLight(color=[1.0, 1.0, 1.0], intensity=2.5)
    lpose = np.eye(4)
    lpose[:3, 3] = [size, size, size]
    scene.add(light, pose=lpose)

    r = pyrender.OffscreenRenderer(viewport_width=RES, viewport_height=RES)
    color, _ = r.render(scene, flags=pyrender.RenderFlags.RGBA)
    r.delete()
    return Image.fromarray(color, "RGBA")


def add_white_outline(rgba: Image.Image, thickness: int) -> Image.Image:
    """Dilate the alpha mask, paint it white, paste original on top."""
    alpha = rgba.split()[-1]
    # MaxFilter dilates; size must be odd.
    k = max(3, thickness * 2 + 1)
    dilated = alpha.filter(ImageFilter.MaxFilter(k))
    outline = Image.new("RGBA", rgba.size, (255, 255, 255, 0))
    outline.putalpha(dilated)
    # Tint to white where mask is opaque
    out_rgb = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
    out_rgb.putalpha(dilated)
    out_rgb.alpha_composite(rgba)
    return out_rgb


# Sampled from the Create logo's flat blue ring (108, 180, 229).
BG_FILL = (108, 180, 229, 255)


def composite_on_background(fg: Image.Image, bg_path: Path) -> Image.Image:
    bg = Image.open(bg_path).convert("RGBA")
    side = max(fg.size)
    s = max(side / bg.width, side / bg.height)
    nw, nh = int(bg.width * s), int(bg.height * s)
    bg = bg.resize((nw, nh), Image.LANCZOS)
    left = (nw - side) // 2
    top = (nh - side) // 2
    bg = bg.crop((left, top, left + side, top + side))
    if FILL_CORNERS:
        canvas = Image.new("RGBA", bg.size, BG_FILL)
        canvas.alpha_composite(bg)
        canvas.alpha_composite(fg)
        return canvas.convert("RGB")
    # Theme-adaptive: keep the bg's circular alpha intact so the host UI
    # (Modrinth card, Discord embed, etc) shows through the corners.
    bg.alpha_composite(fg)
    return bg


def main():
    targets = sys.argv[1:] or list(OBJ_TO_TEX.keys())
    for name in targets:
        tex = OBJ_TO_TEX[name]
        obj_path = MODELS / f"{name}.obj"
        tex_path = TEXTURES / tex
        print(f"[+] {name}: {obj_path.name} + {tex}")
        mesh = load_geometry(obj_path)
        rendered = render_mesh(mesh, tex_path)
        outlined = add_white_outline(rendered, OUTLINE_PX)
        final = composite_on_background(outlined, BG_PATH)
        if final.size != (OUTPUT_SIZE, OUTPUT_SIZE):
            final = final.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.LANCZOS)
        out = OUT_DIR / f"{name}.png"
        final.save(out, optimize=True)
        print(f"    -> {out} ({out.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()
