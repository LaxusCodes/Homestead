#!/usr/bin/env python3
"""
Creates Homestead's own art inside an unzipped resource pack:
  - HUD icon font: thirst bar (above hunger), temperature bar + season icon (right corner)
    -> assets/homestead/font/hud.json + textures/font/*.png
  - realistic backpack skin textures (64x64) + matching 3D models
  - wine and must bottle textures + item models, golden leaf art

  python make_assets.py <unzipped pack folder> [path/to/config.yml]
Requires: pip install pillow pyyaml
"""
import json, os, sys
from PIL import Image, ImageDraw

pack = sys.argv[1]
cfg_path = sys.argv[2] if len(sys.argv) > 2 else None
cfg = {}
if cfg_path and os.path.isfile(cfg_path):
    import yaml
    cfg = yaml.safe_load(open(cfg_path, encoding="utf-8")) or {}

hud = cfg.get("hud", {}) or {}
ASCENT_THIRST = int(hud.get("thirst-ascent", 22))   # 22 = hovers a hair above the hunger icons
ASCENT_TEMP = int(hud.get("temp-ascent", 26))       # 26 = slightly higher than the thirst bar

ns = os.path.join(pack, "assets", "homestead")
for d in ("font", "textures/font", "textures/item", "models/item"):
    os.makedirs(os.path.join(ns, d), exist_ok=True)

def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

def draw(grid, pal):
    img = Image.new("RGBA", (len(grid[0]), len(grid)), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            if ch in pal:
                img.putpixel((x, y), hexc(pal[ch]))
    return img

# ------------------------------------------------------------------ seasons (9x9)
SPRING = ["...PPP...", "..PPPPP..", "..PPYPP..", "..PPPPP..", "...PPP...", "....G....", "...GGG...", "....G....", "....G...."]
SUMMER = ["....O....", ".O.....O.", "...YYY...", "..YYYYY..", "O.YYYYY.O", "..YYYYY..", "...YYY...", ".O.....O.", "....O...."]
AUTUMN = ["....L....", "...LLL...", "..LLDLL..", ".LLLDLLL.", "..LLDLL..", ".LLLDLLL.", "..LLDLL..", "....D....", "....S...."]
WINTER = ["....W....", ".W..W..W.", "..W.W.W..", "...CWC...", "WWWWWWWWW", "...CWC...", "..W.W.W..", ".W..W..W.", "....W...."]
PAL_S = {"P": "#ff8fb8", "Y": "#ffd93b", "G": "#4caf50", "O": "#ffa726", "L": "#e8821e", "D": "#9c4a12",
         "S": "#6d4c41", "W": "#f2faff", "C": "#9fd8ff"}

# ------------------------------------------------------------------ thirst bar glyphs (90x9 each)
# One wide glyph per thirst level (0..20): 10 slots, each either an empty slot, a half drop or a full drop.
DROP_SHAPE = [
    "..KKK....",
    ".KBBBK...",
    ".KBBBK...",
    "KBBBBBK..",
    "KBBBBBK..",
    "KBWBBBK..",
    "KBBBBBK..",
    "KBBBBBK..",
    ".KKKKK...",
]
SLAB = [" ", " ", " ", " ", " ", " ", " ", " ", " "]  # blank column (1px gap between slots)

def slot(kind):
    """9x9 column art for one thirst slot."""
    img = Image.new("RGBA", (9, 9), (0, 0, 0, 0))
    full = {"K": (20, 50, 79, 230), "B": (61, 180, 242, 255), "W": (216, 243, 255, 255)}
    # empty slot = dark translucent drop-shaped socket so the bar reads on any background
    empty = {"K": (12, 12, 14, 200), "B": (26, 26, 30, 210), "W": (38, 38, 44, 220)}
    pal = full if kind != "empty" else empty
    for y, row in enumerate(DROP_SHAPE):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            col = pal["K" if ch == "K" else "W" if ch == "W" else "B"]
            if kind == "hard-empty":
                col = (255, 255, 255, 0)
            if kind == "half" and x > 4:
                col = empty["K" if ch == "K" else "W" if ch == "W" else "B"]
            img.putpixel((x, y), col)
    return img

def thirst_bar(level):
    """level 0..20 -> single 90x9 glyph: 10 slots."""
    img = Image.new("RGBA", (90, 9), (0, 0, 0, 0))
    for i in range(10):
        if level >= 2 * i + 2:
            img.paste(slot("full"), (i * 9, 0))
        elif level == 2 * i + 1:
            img.paste(slot("half"), (i * 9, 0))
        else:
            img.paste(slot("empty"), (i * 9, 0))
    return img

# ------------------------------------------------------------------ temperature bar glyphs (45x9 each)
THERMO = [
    ".KKKK....",
    ".KGGK....",
    ".KGGK....",
    ".KGGK....",
    ".KGGK....",
    ".KGGK....",
    ".KMMMK...",
    ".KMMMK...",
    ".KKKKK...",
]
def temp_bar(level):
    """level 0..4 -> 45x9 glyph: thermometer + 36px fill bar."""
    img = Image.new("RGBA", (45, 9), (0, 0, 0, 0))
    colors = [
        [(46, 84, 214, 255), (154, 196, 255, 255)],   # freezing
        [(79, 195, 247, 255), (224, 247, 255, 255)],  # cold
        [(102, 187, 106, 255), (200, 241, 183, 255)], # mild
        [(255, 167, 38, 255), (255, 224, 178, 255)],  # warm
        [(229, 57, 53, 255), (255, 205, 210, 255)],   # hot
    ][level]
    main, hi = colors
    # fill bar: frame + segment to level
    for x in range(12, 44):
        for y in (1, 7):
            img.putpixel((x, y), (14, 14, 16, 220))
    for y in range(1, 8):
        img.putpixel((11, y), (14, 14, 16, 220))
        img.putpixel((44, y), (14, 14, 16, 220))
    segs = {0: 8, 1: 14, 2: 20, 3: 26, 4: 32}[level]
    for x in range(12, 12 + segs):
        for y in range(2, 7):
            img.putpixel((x, y), main if y > 4 else hi)
    # thermometer
    for y, row in enumerate(THERMO):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            col = main if ch == "M" else (36, 36, 38, 255) if ch == "K" else (207, 207, 207, 255)
            if ch == "G":
                col = hi if y <= 5 else main if y >= 8 else main
            img.putpixel((x, y), col)
    return img

# -------------------------------------------------------------- assemble HUD atlases + font json
ICON_ROWS = [slot("hard-empty"), slot("hard-empty"), slot("hard-empty"),
             draw(SPRING, PAL_S), draw(SUMMER, PAL_S), draw(AUTUMN, PAL_S), draw(WINTER, PAL_S)]

thirst_glyphs = [thirst_bar(v) for v in range(21)]          # E000..E014
# split 21 glyphs into two rows of the atlas (max columns 11 -> stays under 1024 px wide)
THIRST_ROW1 = 11
atlas_t = Image.new("RGBA", (THIRST_ROW1 * 90, 18), (0, 0, 0, 0))
for i, g in enumerate(thirst_glyphs):
    col, row = i % THIRST_ROW1, i // THIRST_ROW1
    atlas_t.paste(g, (col * 90, row * 9))
atlas_t.save(os.path.join(ns, "textures/font/hud_thirst.png"))
chars_thirst = ["".join(chr(0xE000 + r * THIRST_ROW1 + c) for c in range(THIRST_ROW1 if r == 0 else 21 - THIRST_ROW1))
                for r in range(2)]

atlas_p = Image.new("RGBA", (5 * 45, 9), (0, 0, 0, 0))
for v in range(5):
    atlas_p.paste(temp_bar(v), (v * 45, 0))
atlas_p.save(os.path.join(ns, "textures/font/hud_temp.png"))
chars_temp = ["".join(chr(0xE015 + v) for v in range(5))]

atlas_s = Image.new("RGBA", (4 * 9, 9), (0, 0, 0, 0))
for i, ic in enumerate(ICON_ROWS[3:7]):
    atlas_s.paste(ic, (i * 9, 0))
atlas_s.save(os.path.join(ns, "textures/font/hud_season.png"))
chars_season = ["".join(chr(0xE01A + i) for i in range(4))]

# invisible spacer glyphs so the HUD bars can be pixel-locked horizontally
for name, w, cp in (("hud_space9", 9, 0xE020), ("hud_space3", 3, 0xE021)):
    Image.new("RGBA", (w, 1), (0, 0, 0, 0)).save(os.path.join(ns, f"textures/font/{name}.png"))

font = {
    "providers": [
        {"type": "bitmap", "file": "homestead:font/hud_thirst.png", "ascent": ASCENT_THIRST, "height": 9, "chars": chars_thirst},
        {"type": "bitmap", "file": "homestead:font/hud_temp.png", "ascent": ASCENT_TEMP, "height": 9, "chars": chars_temp},
        {"type": "bitmap", "file": "homestead:font/hud_season.png", "ascent": ASCENT_TEMP, "height": 9, "chars": chars_season},
        {"type": "bitmap", "file": "homestead:font/hud_space9.png", "ascent": 0, "height": 1, "chars": ["\ue020"]},
        {"type": "bitmap", "file": "homestead:font/hud_space3.png", "ascent": 0, "height": 1, "chars": ["\ue021"]},
    ]
}
json.dump(font, open(os.path.join(ns, "font/hud.json"), "w"), indent=2)

# remove the old single-atlas HUD if a previous run left it
old = os.path.join(ns, "textures/font/hud.png")
if os.path.isfile(old):
    os.remove(old)

# ------------------------------------------------------------ bottles (16x16)
WINE = [
    "................",
    "......CCCC......",
    "......CBBC......",
    "......GBBG......",
    "......GBBG......",
    "......GBBG......",
    ".....GGBBGG.....",
    "....GGWLLLGG....",
    "....GWLLLLLG....",
    "....GWLLLLLG....",
    "....GLLLLLLG....",
    "....GLLLLLDG....",
    "....GLLLLLDG....",
    "....GLLLLDDG....",
    ".....GGGGGG.....",
    "................",
]
MUST = [
    "................",
    "................",
    ".....CCCCCC.....",
    ".....CBBBBC.....",
    "......GBBG......",
    ".....GGBBGG.....",
    "....GWWWWWWG....",
    "...GWLLLLLLLG...",
    "...GLLLLLLLLG...",
    "...GLLLLLLLLG...",
    "...GLLLLLLLDG...",
    "...GLLLLLLLDG...",
    "....GLLLLLDG....",
    ".....GGGGGG.....",
    "................",
    "................",
]
def bottle(grid, liquid, dark, glass="#2b3a2b", cork="#b8864b", cork_dark="#8d6232"):
    pal = {"G": glass, "C": cork, "B": cork_dark, "L": liquid, "D": dark, "W": "#ffffff"}
    img = draw(grid, pal)
    for y in range(img.height):
        for x in range(img.width):
            if img.getpixel((x, y)) == (255, 255, 255, 255):
                r, g, b, _ = hexc(liquid)
                img.putpixel((x, y), (min(255, r + 70), min(255, g + 70), min(255, b + 70), 255))
    return img

kinds = {
    "berry":  ("#7b1fa2", "#4a0f66"),
    "apple":  ("#e6c229", "#a98a12"),
    "glow":   ("#ff8f1f", "#b35a00"),
}
for k, (liq, dark) in kinds.items():
    bottle(WINE, liq, dark).save(os.path.join(ns, f"textures/item/wine_{k}.png"))
    bottle(MUST, liq, dark, glass="#5b6b73", cork="#d9d2c0", cork_dark="#b9b19b").save(
        os.path.join(ns, f"textures/item/must_{k}.png"))
    for kind in ("wine", "must"):
        json.dump({"parent": "minecraft:item/generated",
                   "textures": {"layer0": f"homestead:item/{kind}_{k}"}},
                  open(os.path.join(ns, f"models/item/{kind}_{k}.json"), "w"), indent=2)

# ------------------------------------------------------- golden leaf
LEAF = [
    "................", "..........KKK...", ".......KKKGGHK..", ".....KKGGGGGHK..",
    "....KGGHGGGGGK..", "...KGGHGGGGGGK..", "...KGHGGDGGGGK..", "..KGGGGDGGGGK...",
    "..KGGGDGGGGGK...", "..KGGDGGGGGK....", "..KGDGGGGKK.....", "...KDGGKK.......",
    "...KDKK.........", "..KSK...........", ".KSK............", "................",
]
draw(LEAF, {"K": "#6b4a06", "G": "#f2b720", "H": "#ffe27a", "D": "#a8730b", "S": "#7a5a1e"}).save(
    os.path.join(ns, "textures/item/golden_leaf.png"))
json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "homestead:item/golden_leaf"}},
          open(os.path.join(ns, "models/item/golden_leaf.json"), "w"), indent=2)

# =================================================================================
# backpack skins - 64x64 textures + 3D models with straps, stitching, buckles
# =================================================================================
SKINS = {
    "classic":  dict(body="#8a5a28", dark="#5e3c17", light="#a87238", flap="#96672f", pocket="#5e3c17",
                     strap="#3d2510", buckle="#d9b23e", stud="#8f6a1c", roll=None),
    "explorer": dict(body="#9c3d2c", dark="#6e291d", light="#b74f39", flap="#c98a33", pocket="#6e291d",
                     strap="#3a2412", buckle="#edd27b", stud="#9a7a22", roll="#4c7a38"),
    "shadow":   dict(body="#272b33", dark="#181b21", light="#3d4350", flap="#333945", pocket="#22262e",
                     strap="#15171c", buckle="#d9b23e", stud="#7a8191", roll=None, eyes=True),
    "camper":   dict(body="#cd7428", dark="#9c5417", light="#e29241", flap="#2e6cb2", pocket="#9c5417",
                     strap="#264b84", buckle="#edd27b", stud="#8a6218", roll="#2e6cb2"),
    "forest":   dict(body="#3d683a", dark="#2c4d29", light="#54804d", flap="#688b36", pocket="#2c4d29",
                     strap="#38250f", buckle="#d9b23e", stud="#7d6a22", roll="#39592a"),
}

def cd(c):
    return hexc(c)

def vertical_gradient(w, h, top, bottom):
    img = Image.new("RGBA", (w, h))
    r0, g0, b0, _ = hexc(top)
    r1, g1, b1, _ = hexc(bottom)
    for y in range(h):
        t = y / max(1, h - 1)
        col = (int(r0 + (r1 - r0) * t), int(g0 + (g1 - g0) * t), int(b0 + (b1 - b0) * t), 255)
        for x in range(w):
            img.putpixel((x, y), col)
    return img

def add_grain(img, amount=8):
    """subtle leather/canvas noise so flat colours don't look plastic."""
    import random
    rnd = random.Random(1234)
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = img.getpixel((x, y))
            if a == 0:
                continue
            n = rnd.randint(-amount, amount)
            img.putpixel((x, y), (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), a))

def stitches(img, x0, y0, w, col="#000000", alpha=110, dash=1, gap=2):
    """horizontal dashed stitch line"""
    c = hexc(col, alpha)
    x = x0
    while x < x0 + w:
        for dx in range(dash):
            if x + dx < img.width:
                img.putpixel((x + dx, y0), c)
        x += dash + gap

def stitches_v(img, y0, x0, h, col="#000000", alpha=110):
    c = hexc(col, alpha)
    y = y0
    while y < y0 + h:
        img.putpixel((x0, y), c)
        img.putpixel((x0 + 1, y), c)
        y += 3

def patch(img, x0, y0, w, h, top, bottom, grain=8):
    base = vertical_gradient(w, h, top, bottom)
    add_grain(base, grain)
    img.paste(base, (x0, y0))

def leather_patch(img, x0, y0, w, h, top, bottom, seams=True, grain=9):
    patch(img, x0, y0, w, h, top, bottom, grain)
    if seams:
        stitches(img, x0, y0 + 1, w, col=bottom, alpha=150)
        stitches(img, x0, y0 + h - 2, w, col=bottom, alpha=150)
        stitches_v(img, y0 + 1, x0 + 1, h - 2, col=bottom, alpha=150)
        stitches_v(img, y0 + 1, x0 + w - 3, h - 2, col=bottom, alpha=150)

# 64x64 layout (model faces reference these regions)
#   back/south (what people see): x  0..15, y 0..19   (16x20)
#   front/north (against body):   x 16..31, y 0..19   (16x20)
#   top:      x 32..47, y 0..7    bottom: x 32..47, y 8..15  (16x8 each)
#   west side: x 48..55, y 0..19 (8x20)   east side: x 56..63, y 0..19 (8x20)
#   flap tile: x  0..11, y 20..27 (12x8)   strap tile: x 12..19, y 20..27 (8x8)
#   roll tile: x 20..27, y 20..27 (8x8)    bedroll face: x 28..43, y 20..27 (16x8)
def skin_texture(c):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    body, dark, light, flap = c["body"], c["dark"], c["light"], c["flap"]

    # ---- outward face (south): body + hem + pouch
    leather_patch(img, 0, 0, 16, 20, light, body)
    # darker band along the bottom hem
    hb = vertical_gradient(16, 5, c["pocket"], dark)
    add_grain(hb, 6)
    img.paste(hb, (0, 15))
    stitches(img, 0, 14, 16, col="#241708", alpha=160)
    # top flap with fold shadow
    fl = vertical_gradient(16, 6, flap, c["pocket"])
    add_grain(fl, 6)
    img.paste(fl, (0, 0))
    stitches(img, 0, 5, 16, col="#121212", alpha=170)
    for x in (1, 14):
        img.putpixel((x, 3), hexc(dark, 200))  # fold ends
    # buckles: two brass plates + metal pin highlight
    for bx in (3, 11):
        img.paste(vertical_gradient(2, 3, "#ffe28a", "#a97b1c"), (bx, 11))
        img.putpixel((bx, 11), hexc("#fff2b0"))
    # bedroll shadow under the roll (when present) else a modest top strap
    if not c.get("roll"):
        for x in range(2, 14, 3):
            img.putpixel((x, 1), hexc(c["strap"], 180))

    # ---- inward face (north): dimmer + straps cross
    leather_patch(img, 16, 0, 16, 20, c["body"], c["dark"], seams=False, grain=6)
    for x in range(18, 30, 4):
        for y in range(0, 20):
            img.putpixel((x, y), hexc(dark if y % 3 else light, 190))
    img.paste(vertical_gradient(16, 3, dark, c["body"]), (16, 0))

    # ---- top: rim light + grain; bottom: heavy shadow
    patch(img, 32, 0, 16, 8, light, body, 7)
    stitches(img, 32, 6, 16, col="#241708", alpha=140)
    patch(img, 32, 8, 16, 8, dark, "#1c130b", 7)

    # ---- sides (west left-facing carry strap, east plain)
    leather_patch(img, 48, 0, 8, 20, light, dark, seams=False, grain=7)
    leather_patch(img, 56, 0, 8, 20, c["body"], dark, seams=False, grain=7)
    # side carry strap on the west face
    for y in range(3, 17):
        img.putpixel((50, y), hexc(c["strap"]))
        img.putpixel((51, y), hexc("#1d1208"))
    img.paste(vertical_gradient(2, 2, "#ffe28a", "#a97b1c"), (49, 9))

    # ---- flap tile (pocket element faces)
    fl2 = vertical_gradient(12, 8, flap, c["pocket"])
    add_grain(fl2, 6)
    img.paste(fl2, (0, 20))
    stitches(img, 0, 21, 12, col="#121212", alpha=160)
    img.paste(vertical_gradient(2, 3, "#ffe28a", "#a97b1c"), (5, 23))
    img.putpixel((5, 23), hexc("#fff2b0"))

    # ---- strap tile (vertical straps on the back)
    patch(img, 12, 20, 8, 8, c["strap"], dark, 5)
    for x in (13, 16):
        stitches_v(img, 21, x, 6, col="#0d0a06", alpha=170)
    for y in (22, 25):
        img.putpixel((14, y), hexc("#d9b23e"))
        img.putpixel((18, y), hexc("#d9b23e"))

    # ---- roll tile + roll face (bedroll canvas with quilting)
    roll_base = c.get("roll") or body
    patch(img, 20, 20, 8, 8, roll_base, dark, 5)
    for y in (22, 25):
        stitches(img, 20, y, 8, col="#0d0a06", alpha=160)
    patch(img, 28, 20, 16, 8, roll_base, dark, 5)
    for x in range(30, 43, 4):
        stitches_v(img, 21, x, 6, col="#0d0a06", alpha=160)
    return img

def faces(uv):
    return {f: {"uv": n_uv(uv), "texture": "#0"} for f in ("north", "south", "east", "west", "up", "down")}

def n_uv(uv):
    """Pixel coords on the 64x64 texture -> normalized model UV (0..16). Minecraft ignores
    the Blockbench-style texture_size tag: model UV is always normalized."""
    return [round(v / 4.0, 3) for v in uv]

def skin_model(name, roll):
    els = [
        # main pouch
        {"from": [4, 3, 6], "to": [12, 13, 10], "faces": {
            "north": {"uv": n_uv([16, 0, 32, 20]), "texture": "#0"}, "south": {"uv": n_uv([0, 0, 16, 20]), "texture": "#0"},
            "east": {"uv": n_uv([56, 0, 64, 20]), "texture": "#0"}, "west": {"uv": n_uv([48, 0, 56, 20]), "texture": "#0"},
            "up": {"uv": n_uv([32, 0, 48, 8]), "texture": "#0"}, "down": {"uv": n_uv([32, 8, 48, 16]), "texture": "#0"}}},
        # lower pocket (against the outward face)
        {"from": [4.5, 3, 10], "to": [11.5, 7.5, 11], "faces": faces([0, 20, 12, 28])},
        # side straps (both sides)
        {"from": [3.4, 4.5, 6.5], "to": [4.0, 12.5, 9.5], "faces": faces([12, 20, 20, 28])},
        {"from": [12.0, 4.5, 6.5], "to": [12.6, 12.5, 9.5], "faces": faces([12, 20, 20, 28])},
    ]
    if roll:
        els.append({"from": [4, 13, 6], "to": [12, 15, 10], "faces": {
            "north": {"uv": n_uv([28, 20, 44, 28]), "texture": "#0"}, "south": {"uv": n_uv([28, 20, 44, 28]), "texture": "#0"},
            "east": {"uv": n_uv([20, 20, 28, 24]), "texture": "#0"}, "west": {"uv": n_uv([20, 20, 28, 24]), "texture": "#0"},
            "up": {"uv": n_uv([20, 20, 28, 28]), "texture": "#0"}, "down": {"uv": n_uv([20, 24, 28, 28]), "texture": "#0"}}})
    return {
        "textures": {"0": f"homestead:item/backpack_{name}", "particle": f"homestead:item/backpack_{name}"},
        "texture_size": [64, 64],
        "elements": els,
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

for name, c in SKINS.items():
    skin_texture(c).save(os.path.join(ns, f"textures/item/backpack_{name}.png"))
    json.dump(skin_model(name, c["roll"]), open(os.path.join(ns, f"models/item/backpack_{name}.json"), "w"), indent=2)
print("backpack skins:", ", ".join(SKINS))
print("homestead assets written")
