#!/usr/bin/env python3
"""
Procedural, higher-detail Homestead backpacks.

  python make_backpack.py <unzipped pack folder>

Writes assets/homestead/models/item/backpack_<skin>.json and textures/item/backpack_<skin>.png
for classic / explorer / shadow / camper / forest.

Compared with the old 4-box, 64px version this builds an 18-22 part model (reinforced base, lid with
overhang and front flap, buckled straps, front pocket with its own flap, bottle pockets, shoulder
straps against the back, grab handle, bedroll / top roll per skin) and paints it on a 256x256 atlas
(about 6px per model unit) with leather grain, edge bevels, stitching, brass hardware and a small
emblem per skin. Needs: pip install pillow numpy
"""
import json, math, os, random, sys
import numpy as np
from PIL import Image

ATLAS = 256

# ----------------------------------------------------------------------------- palettes
def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)

SKINS = {
    "classic":  dict(main="#8a5a28", dark="#5a3817", flap="#96672f", pocket="#6b4420", strap="#3d2510",
                     metal="#d9b23e", roll=None, roll_pos=None, emblem="brand"),
    "explorer": dict(main="#9c3d2c", dark="#64261b", flap="#b9722f", pocket="#78301f", strap="#3a2412",
                     metal="#edd27b", roll="#4c7a38", roll_pos="bottom", emblem="compass"),
    "shadow":   dict(main="#2b2f38", dark="#16181d", flap="#363c49", pocket="#23262d", strap="#14161a",
                     metal="#aab2c3", roll=None, roll_pos=None, emblem="moon"),
    "camper":   dict(main="#cd7428", dark="#8f4d15", flap="#2e6cb2", pocket="#a85d1c", strap="#264b84",
                     metal="#edd27b", roll="#2e6cb2", roll_pos="top", emblem="tent"),
    "forest":   dict(main="#3d683a", dark="#27441f", flap="#688b36", pocket="#2f5030", strap="#38250f",
                     metal="#d9b23e", roll="#39592a", roll_pos="top", emblem="leaf"),
}

# ----------------------------------------------------------------------------- geometry
# (name, from, to, material). Model units; the pack hangs from z=6 (against the wearer) to z~11.7 (outward).
def parts_for(c):
    P = [
        ("body",     [4, 3, 6],        [12, 13, 10],      "main"),
        ("base",     [3.6, 2.4, 5.6],  [12.4, 3.6, 10.4], "dark"),
        ("lid_top",  [3.7, 13.0, 5.7], [12.3, 13.8, 10.6], "flap"),
        ("lid_flap", [3.9, 8.6, 10.0], [12.1, 13.0, 10.6], "flap"),
        ("pocket",   [4.6, 3.6, 10.0], [11.4, 8.0, 11.5],  "pocket"),
        ("pocket_lid", [4.4, 7.2, 10.0], [11.6, 8.4, 11.7], "flap"),
        ("strap_l",  [5.2, 8.4, 10.6], [6.4, 13.3, 10.85], "strap"),
        ("strap_r",  [9.6, 8.4, 10.6], [10.8, 13.3, 10.85], "strap"),
        ("buckle_l", [5.1, 9.0, 10.85], [6.5, 10.4, 11.1], "metal"),
        ("buckle_r", [9.5, 9.0, 10.85], [10.9, 10.4, 11.1], "metal"),
        ("bottle_l", [2.7, 3.6, 6.8],  [4.0, 8.8, 9.6],   "pocket"),
        ("bottle_r", [12.0, 3.6, 6.8], [13.3, 8.8, 9.6],  "pocket"),
        ("bottle_rim_l", [2.6, 8.4, 6.7],  [4.1, 9.0, 9.7], "strap"),
        ("bottle_rim_r", [11.9, 8.4, 6.7], [13.4, 9.0, 9.7], "strap"),
        ("shoulder_l", [4.8, 3.2, 5.2], [6.5, 13.0, 6.0], "strap"),
        ("shoulder_r", [9.5, 3.2, 5.2], [11.2, 13.0, 6.0], "strap"),
        ("handle",   [6.8, 13.8, 7.4], [9.2, 14.5, 8.6],   "strap"),
    ]
    if c["roll"] and c["roll_pos"] == "top":
        P += [("roll", [3.8, 13.8, 6.0], [12.2, 15.7, 10.2], "roll"),
              ("roll_strap_l", [5.0, 13.7, 5.9], [5.7, 15.8, 10.3], "strap"),
              ("roll_strap_r", [10.3, 13.7, 5.9], [11.0, 15.8, 10.3], "strap")]
        P = [p for p in P if p[0] != "handle"]
    if c["roll"] and c["roll_pos"] == "bottom":
        P += [("roll", [3.7, 0.5, 5.8], [12.3, 2.4, 10.0], "roll"),
              ("roll_strap_l", [5.0, 0.4, 5.7], [5.7, 2.5, 10.1], "strap"),
              ("roll_strap_r", [10.3, 0.4, 5.7], [11.0, 2.5, 10.1], "strap")]
    return P

# ----------------------------------------------------------------------------- atlas packing
def net_size(frm, to, ppu):
    w = max(1, round((to[0] - frm[0]) * ppu)); h = max(1, round((to[1] - frm[1]) * ppu))
    d = max(1, round((to[2] - frm[2]) * ppu))
    return w, h, d

def pack(parts, ppu):
    rects = []
    for name, frm, to, mat in parts:
        w, h, d = net_size(frm, to, ppu)
        rects.append([name, 2 * (d + w) + 1, d + h + 1, (w, h, d)])
    rects.sort(key=lambda r: -r[2])
    x = y = rowh = 0
    out = {}
    for name, rw, rh, dims in rects:
        if x + rw > ATLAS:
            x, y, rowh = 0, y + rowh, 0
        if y + rh > ATLAS or rw > ATLAS:
            return None
        out[name] = (x, y, dims)
        x += rw; rowh = max(rowh, rh)
    return out

def best_layout(parts):
    ppu = 9.0
    while ppu > 2:
        lay = pack(parts, ppu)
        if lay:
            return ppu, lay
        ppu -= 0.25
    raise SystemExit("cannot pack backpack parts")

def regions(x, y, w, h, d):
    return {
        "up":    (x + d, y, w, d),
        "down":  (x + d + w, y, w, d),
        "west":  (x, y + d, d, h),
        "north": (x + d, y + d, w, h),
        "east":  (x + d + w, y + d, d, h),
        "south": (x + 2 * d + w, y + d, w, h),
    }

# ----------------------------------------------------------------------------- painting
def fbm(rng, w, h, octaves=3):
    acc = np.zeros((h, w), dtype=np.float32)
    amp = 1.0
    tot = 0.0
    for o in range(octaves):
        s = 2 ** (o + 1)
        gw, gh = max(2, w // s + 2), max(2, h // s + 2)
        g = rng.random((gh, gw)).astype(np.float32)
        im = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        acc += np.asarray(im, dtype=np.float32) / 255.0 * amp
        tot += amp; amp *= 0.5
    return acc / tot - 0.5

FACE_LIGHT = {"up": 1.07, "down": 0.74, "north": 0.9, "south": 1.0, "east": 0.88, "west": 0.94}

def base_patch(rng, w, h, color, face, grain=0.07, weave=False):
    col = hexc(color)
    n = fbm(rng, w, h)
    fine = rng.normal(0, 1, (h, w)).astype(np.float32)
    k = (1 + n * grain * 3.2 + fine * grain * 0.35)[..., None]
    img = col[None, None, :] * k * FACE_LIGHT[face]
    if weave:
        yy, xx = np.mgrid[0:h, 0:w]
        img *= (1 + 0.05 * (((xx // 2) + (yy // 2)) % 2) - 0.025)[..., None]
    return img

def bevel(img, amount=0.18, width=1):
    h, w, _ = img.shape
    for i in range(width):
        f = 1 - amount * (1 - i / max(1, width))
        img[i, :, :] *= f; img[h - 1 - i, :, :] *= f * 0.92
        img[:, i, :] *= f; img[:, w - 1 - i, :] *= f * 0.92
    return img

def stitch_rect(img, inset, col, dash, gap, alpha=0.7):
    h, w, _ = img.shape
    if w < 2 * inset + 4 or h < 2 * inset + 4:
        return
    c = col * 0.55
    def put(x, y):
        img[y, x, :] = img[y, x, :] * (1 - alpha) + c * alpha
    for x in range(inset, w - inset, dash + gap):
        for dx in range(dash):
            if x + dx < w - inset:
                put(x + dx, inset); put(x + dx, h - 1 - inset)
    for y in range(inset, h - inset, dash + gap):
        for dy in range(dash):
            if y + dy < h - inset:
                put(inset, y + dy); put(w - 1 - inset, y + dy)

EMBLEMS = {
    "brand":   ["#.#.#", "#.#.#", "#####", "#.#.#", "#.#.#"],
    "compass": ["..#..", ".###.", "#####", ".###.", "..#.."],
    "moon":    [".###.", "##...", "##...", "##...", ".###."],
    "tent":    ["..#..", ".###.", ".#.#.", "#####", "#...#"],
    "leaf":    ["...##", "..###", ".###.", "###..", "#.#.."],
}

def emblem(img, name, color, ppu):
    pat = EMBLEMS[name]
    h, w, _ = img.shape
    s = max(2, int(ppu * 0.42))
    ox = (w - 5 * s) // 2
    oy = int(h * 0.30) - 2 * s // 2
    col = hexc(color)
    for j, row in enumerate(pat):
        for i, ch in enumerate(row):
            if ch != "#":
                continue
            x0, y0 = ox + i * s, oy + j * s
            if 0 <= y0 and y0 + s <= h and 0 <= x0 and x0 + s <= w:
                img[y0:y0 + s, x0:x0 + s, :] = col * 0.97
                img[y0:y0 + 1, x0:x0 + s, :] *= 1.12          # top highlight
                img[y0 + s - 1:y0 + s, x0:x0 + s, :] *= 0.7  # bottom shadow

def paint_face(rng, name, mat, face, w, h, c, ppu):
    if w < 1 or h < 1:
        return np.zeros((1, 1, 3), dtype=np.float32)
    color = {"main": c["main"], "dark": c["dark"], "flap": c["flap"], "pocket": c["pocket"],
             "strap": c["strap"], "metal": c["metal"], "roll": c["roll"] or c["main"]}[mat]
    dash = max(2, round(ppu * 0.45)); gap = max(2, round(ppu * 0.4))
    if mat == "metal":
        img = base_patch(rng, w, h, color, face, grain=0.02)
        yy = np.linspace(1.18, 0.72, h)[:, None, None]
        img = img * yy
        img = bevel(img, 0.35, 1)
        return img
    weave = mat == "roll"
    img = base_patch(rng, w, h, color, face, weave=weave)
    if mat == "roll":
        if face in ("east", "west"):
            # rolled-up bedroll end cap: concentric rings
            yy, xx = np.mgrid[0:h, 0:w]
            r = np.maximum(np.abs(xx - (w - 1) / 2) / max(1, w / 2), np.abs(yy - (h - 1) / 2) / max(1, h / 2))
            ring = (np.floor(r * 5) % 2)[..., None]
            img *= 1 - 0.16 * ring
        else:
            stitch_rect(img, 1, hexc(color), dash, gap, 0.4)
    elif mat == "strap":
        img = bevel(img, 0.25, 1)
        if w > 5 and h > 5:
            cx = w // 2
            for y in range(0, h, dash + gap):
                for dy in range(dash):
                    if y + dy < h:
                        img[y + dy, max(0, cx - 1):cx + 1, :] *= 0.55
    else:
        img = bevel(img, 0.2, max(1, round(ppu / 5)))
        if name in ("body", "lid_top", "lid_flap", "pocket", "pocket_lid", "base") and face in ("south", "north", "up", "east", "west"):
            stitch_rect(img, max(2, round(ppu * 0.5)), hexc(color), dash, gap)
        if name == "lid_flap" and face == "south":
            emblem(img, c["emblem"], c["metal"], ppu)
        if name == "pocket_lid" and face == "south":
            # brass stud + lower hem
            sx = w // 2
            img[h // 2 - 1:h // 2 + 2, sx - 1:sx + 2, :] = hexc(c["metal"]) * 1.05
    return img

def build(skin, c, out_pack):
    parts = parts_for(c)
    ppu, lay = best_layout(parts)
    rng = np.random.default_rng(abs(hash(skin)) % (2 ** 31) if False else sum(ord(ch) for ch in skin) * 7919)
    atlas = np.zeros((ATLAS, ATLAS, 4), dtype=np.float32)
    elements = []
    for name, frm, to, mat in parts:
        x, y, (w, h, d) = lay[name]
        regs = regions(x, y, w, h, d)
        faces = {}
        for face, (rx, ry, rw, rh) in regs.items():
            patch = paint_face(rng, name, mat, face, rw, rh, c, ppu)
            ph, pw, _ = patch.shape
            atlas[ry:ry + ph, rx:rx + pw, :3] = np.clip(patch, 0, 255)
            atlas[ry:ry + ph, rx:rx + pw, 3] = 255
            u1, v1, u2, v2 = rx, ry, rx + rw, ry + rh
            faces[face] = {"uv": [round(u1 / ATLAS * 16, 4), round(v1 / ATLAS * 16, 4),
                                  round(u2 / ATLAS * 16, 4), round(v2 / ATLAS * 16, 4)], "texture": "#0"}
        elements.append({"name": name, "from": frm, "to": to, "faces": faces})
    img = Image.fromarray(np.clip(atlas, 0, 255).astype(np.uint8), "RGBA")
    tex_dir = os.path.join(out_pack, "assets", "homestead", "textures", "item")
    mod_dir = os.path.join(out_pack, "assets", "homestead", "models", "item")
    os.makedirs(tex_dir, exist_ok=True); os.makedirs(mod_dir, exist_ok=True)
    img.save(os.path.join(tex_dir, f"backpack_{skin}.png"))
    model = {
        "textures": {"0": f"homestead:item/backpack_{skin}", "particle": f"homestead:item/backpack_{skin}"},
        "texture_size": [ATLAS, ATLAS],
        "elements": elements,
        "display": {
            "gui": {"rotation": [30, 45, 0], "translation": [0, -1, 0], "scale": [0.62, 0.62, 0.62]},
            "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 180, 0], "scale": [0.8, 0.8, 0.8]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.5, 0.5, 0.5]},
            "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.5, 0.5, 0.5]},
            "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
            "firstperson_lefthand": {"rotation": [0, 135, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
        },
    }
    with open(os.path.join(mod_dir, f"backpack_{skin}.json"), "w") as f:
        json.dump(model, f, indent=2)
    return len(parts), ppu

def main(pack):
    for skin, c in SKINS.items():
        n, ppu = build(skin, c, pack)
        print(f"backpack {skin}: {n} parts, {ppu:.2f}px/unit")

if __name__ == "__main__":
    main(sys.argv[1])
