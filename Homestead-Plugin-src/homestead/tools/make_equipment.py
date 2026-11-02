#!/usr/bin/env python3
"""
Armour-layer backpacks for Minecraft 26.x (equippable component).

  python make_equipment.py <unzipped pack folder>

Per skin writes
  assets/homestead/equipment/backpack_<skin>.json
  assets/homestead/textures/entity/equipment/humanoid/backpack_<skin>.png   (256x128 = 4x the vanilla 64x32)

The texture uses the vanilla chestplate ("humanoid") body layout: the pack is painted on the back face,
shoulder straps on the front and top, and only the body is painted - the arms stay transparent.
The client draws it like a chestplate: visible in F5, never in first person.
Needs: pip install pillow numpy
"""
import importlib.util, json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("make_backpack", os.path.join(HERE, "make_backpack.py"))
mb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mb)

S = 4                      # texture scale vs vanilla 64x32
W, H = 64 * S, 32 * S
PPU = 4.0                  # detail scale passed to the painter


def put(tex, x, y, w, h, name, mat, face, c, rng):
    """Paint one rectangle (in vanilla texture pixels x S) with the backpack painter."""
    px, py, pw, ph = int(x * S), int(y * S), int(w * S), int(h * S)
    patch = mb.paint_face(rng, name, mat, face, pw, ph, c, PPU)
    patch = np.clip(patch, 0, 255)
    hh, ww, _ = patch.shape
    tex[py:py + hh, px:px + ww, :3] = patch
    tex[py:py + hh, px:px + ww, 3] = 255


def shade(tex, x0, y0, x1, y1, k):
    """Multiply the RGB of a rectangle (texels) by k - k may be a scalar or an (h, 1) column."""
    x0, y0 = max(0, x0), max(0, y0)
    reg = tex[y0:y1, x0:x1, :3]
    if reg.size == 0:
        return
    kk = k if np.isscalar(k) else k[: reg.shape[0]][:, :, None]
    mask = (tex[y0:y1, x0:x1, 3] > 0)[..., None]
    reg[:] = np.where(mask, reg * kk, reg)


def add_depth(tex):
    """
    An armour layer is a flat shell, so the pack volume is faked with light: a top-to-bottom light fall-off,
    ambient occlusion at the rim, contact shadows under the lid / pocket, bright top lips, and the
    pack's profile (lid bulge, pocket bulge, bottle pockets) wrapped onto the side faces.
    """
    s = S
    bx, by = 32 * s, 20 * s            # back face origin (texels)
    bw, bh = 8 * s, 12 * s

    # 1. light fall-off down the back face (top lit, bottom in shade)
    ramp = np.linspace(1.10, 0.80, bh)[:, None]
    shade(tex, bx, by, bx + bw, by + bh, ramp)

    # 2. contact shadows: under the lid flap, around + under the front pocket, under the pocket lid
    shade(tex, bx + int(0.25 * s), by + int(3.4 * s), bx + int(7.75 * s), by + int(3.4 * s) + int(0.9 * s), 0.62)
    shade(tex, bx + int(1.25 * s), by + int(6.6 * s), bx + int(6.75 * s), by + int(6.6 * s) + int(0.7 * s), 0.60)
    shade(tex, bx + int(1.1 * s), by + int(7.0 * s), bx + int(1.5 * s), by + int(9.6 * s), 0.70)     # pocket left wall
    shade(tex, bx + int(6.5 * s), by + int(7.0 * s), bx + int(6.9 * s), by + int(9.6 * s), 0.70)     # right wall
    shade(tex, bx + int(1.4 * s), by + int(9.2 * s), bx + int(6.6 * s), by + int(9.2 * s) + int(0.7 * s), 0.55)  # under pocket

    # 3. bright top lips (light catching the lid, the pocket lid and the bottle-pocket rims)
    shade(tex, bx + int(0.25 * s), by, bx + int(7.75 * s), by + int(0.5 * s), 1.28)
    shade(tex, bx + int(1.25 * s), by + int(5.5 * s), bx + int(6.75 * s), by + int(5.5 * s) + int(0.35 * s), 1.25)
    shade(tex, bx, by + int(6.0 * s), bx + int(0.8 * s), by + int(6.0 * s) + int(0.35 * s), 1.2)
    shade(tex, bx + int(7.2 * s), by + int(6.0 * s), bx + bw, by + int(6.0 * s) + int(0.35 * s), 1.2)

    # 4. rim ambient occlusion: the pack curves away from the viewer at its edges
    rim = max(2, int(0.6 * s))
    for i in range(rim):
        f = 0.70 + 0.30 * (i / rim)
        shade(tex, bx, by + i, bx + bw, by + i + 1, f * 1.1)             # top rim stays a touch lit
        shade(tex, bx, by + bh - 1 - i, bx + bw, by + bh - i, f * 0.85)
        shade(tex, bx + i, by, bx + i + 1, by + bh, f)
        shade(tex, bx + bw - 1 - i, by, bx + bw - i, by + bh, f * 0.9)

    # 5. side faces (4 x 12 at x=16 west / x=28 east): back edge is the lit bulge, front edge falls to shadow
    for sx, back_left in ((16 * s, True), (28 * s, False)):
        sw, sh = 4 * s, 12 * s
        col = np.linspace(0.62, 1.0, sw)
        if not back_left:
            col = col[::-1]
        for i in range(sw):
            shade(tex, sx + i, by, sx + i + 1, by + sh, float(col[i]))
        # vertical light fall-off on the sides too
        shade(tex, sx, by, sx + sw, by + sh, np.linspace(1.08, 0.82, sh)[:, None])
        # lid bulge at the top of the side, bottle pocket bulge lower down (brighter lips)
        shade(tex, sx, by, sx + sw, by + int(0.5 * s), 1.25)
        shade(tex, sx, by + int(6.0 * s), sx + sw, by + int(6.0 * s) + int(0.35 * s), 1.25)
        shade(tex, sx, by + int(6.4 * s), sx + sw, by + int(6.4 * s) + int(0.5 * s), 0.7)


def build(skin, c, pack):
    rng = np.random.default_rng(sum(ord(ch) for ch in skin) * 104729)
    tex = np.zeros((H, W, 4), dtype=np.float32)

    # ---- back face: 8 x 12 at (32, 20) -------------------------------------------------
    bx, by = 32, 20
    put(tex, bx, by, 8, 12, "base", "dark", "south", c, rng)                    # backing
    put(tex, bx + 0.5, by + 1.0, 7, 9.5, "body", "main", "south", c, rng)       # main bag
    put(tex, bx + 0.25, by + 0.0, 7.5, 3.4, "lid_flap", "flap", "south", c, rng)  # lid + emblem
    put(tex, bx + 1.5, by + 6.0, 5, 3.2, "pocket", "pocket", "south", c, rng)   # front pocket
    put(tex, bx + 1.25, by + 5.5, 5.5, 1.1, "pocket_lid", "flap", "south", c, rng)
    put(tex, bx + 0.0, by + 6.0, 0.8, 3.0, "bottle_l", "pocket", "south", c, rng)  # bottle pockets
    put(tex, bx + 7.2, by + 6.0, 0.8, 3.0, "bottle_r", "pocket", "south", c, rng)
    put(tex, bx + 2.0, by + 2.8, 0.8, 3.2, "strap_l", "strap", "south", c, rng)   # lid straps
    put(tex, bx + 5.2, by + 2.8, 0.8, 3.2, "strap_r", "strap", "south", c, rng)
    put(tex, bx + 1.9, by + 4.1, 1.0, 1.1, "buckle_l", "metal", "south", c, rng)
    put(tex, bx + 5.1, by + 4.1, 1.0, 1.1, "buckle_r", "metal", "south", c, rng)
    if c["roll"] and c["roll_pos"] == "top":
        put(tex, bx, by, 8, 1.7, "roll", "roll", "south", c, rng)
    if c["roll"] and c["roll_pos"] == "bottom":
        put(tex, bx, by + 10.3, 8, 1.7, "roll", "roll", "south", c, rng)
    put(tex, bx, by + 11.0, 8, 1.0, "base", "dark", "south", c, rng)            # reinforced bottom

    # ---- front face: 8 x 12 at (20, 20): shoulder straps + sternum strap ---------------
    fx, fy = 20, 20
    put(tex, fx + 1.0, fy, 1.0, 7.0, "strap_l", "strap", "north", c, rng)
    put(tex, fx + 6.0, fy, 1.0, 7.0, "strap_r", "strap", "north", c, rng)
    put(tex, fx + 1.0, fy + 3.0, 6.0, 0.6, "strap_c", "strap", "north", c, rng)
    put(tex, fx + 3.5, fy + 2.8, 1.0, 1.0, "buckle_c", "metal", "north", c, rng)
    put(tex, fx + 0.8, fy + 6.2, 1.4, 0.9, "buckle_l", "metal", "north", c, rng)
    put(tex, fx + 5.8, fy + 6.2, 1.4, 0.9, "buckle_r", "metal", "north", c, rng)

    # ---- top face: 8 x 4 at (20, 16): straps going over the shoulders -------------------
    put(tex, 20 + 1.0, 16, 1.0, 4, "strap_l", "strap", "up", c, rng)
    put(tex, 20 + 6.0, 16, 1.0, 4, "strap_r", "strap", "up", c, rng)

    # ---- side faces: 4 x 12 each. West (16,20): back is its left edge; east (28,20): right edge.
    put(tex, 16, 20 + 0.5, 2.0, 10.5, "body", "dark", "west", c, rng)
    put(tex, 28 + 2, 20 + 0.5, 2.0, 10.5, "body", "dark", "east", c, rng)

    # ---- bottom face: 8 x 4 at (28, 16)
    put(tex, 28, 16, 8, 4, "base", "dark", "down", c, rng)

    add_depth(tex)
    img = Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8), "RGBA")
    tdir = os.path.join(pack, "assets", "homestead", "textures", "entity", "equipment", "humanoid")
    edir = os.path.join(pack, "assets", "homestead", "equipment")
    os.makedirs(tdir, exist_ok=True)
    os.makedirs(edir, exist_ok=True)
    img.save(os.path.join(tdir, f"backpack_{skin}.png"))
    asset = {"layers": {"humanoid": [{"texture": f"homestead:backpack_{skin}"}]}}
    with open(os.path.join(edir, f"backpack_{skin}.json"), "w") as f:      # 1.21.4+ location
        json.dump(asset, f, indent=2)
    ldir = os.path.join(pack, "assets", "homestead", "models", "equipment")  # 1.21.2 / 1.21.3 location
    os.makedirs(ldir, exist_ok=True)
    with open(os.path.join(ldir, f"backpack_{skin}.json"), "w") as f:
        json.dump(asset, f, indent=2)


def main(pack):
    for skin, c in mb.SKINS.items():
        build(skin, c, pack)
        print(f"equipment backpack_{skin}")


if __name__ == "__main__":
    main(sys.argv[1])
