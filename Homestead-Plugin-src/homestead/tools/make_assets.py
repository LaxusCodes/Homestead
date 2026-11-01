#!/usr/bin/env python3
"""
Creates Homestead's own art inside an unzipped resource pack:
  - HUD icon font: thirst bar (directly above hunger), temperature bar + season icon (right side)
    -> assets/homestead/font/hud.json + textures/font/*.png
  - realistic backpack skin textures (256x256, see make_backpack.py) + matching 3D models
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
ASCENT_THIRST = int(hud.get("thirst-ascent", -16))  # -16 = the row directly above the hunger icons
ASCENT_TEMP = int(hud.get("temp-ascent", -16))      # same row, to the right of the thirst bar

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

# ------------------------------------------------------------------ thirst bar glyphs (81x9 each)
# Same geometry as the vanilla hunger bar: 10 icons, 9px wide on an 8px pitch = 81px, so the glyph
# lines up with the hunger bar pixel for pixel. Like hunger, it empties from the LEFT.
DROP_SHAPE = [
    "...KKK...",
    "..KBBBK..",
    "..KBBBK..",
    ".KBBBBBK.",
    ".KBBBBBK.",
    ".KBWBBBK.",
    ".KBBBBBK.",
    ".KBBBBBK.",
    "..KKKKK..",
]
BAR_W = 81            # 10 icons * 8px pitch + 1
TEMP_W = 45
SEASON_W = 9
LAST_COL_MARKER = (0, 0, 0, 1)   # alpha 1 = invisible, but makes the glyph advance exactly W + 1

def slot(kind):
    """9x9 art for one thirst icon: full / half / empty."""
    img = Image.new("RGBA", (9, 9), (0, 0, 0, 0))
    full = {"K": (20, 50, 79, 235), "B": (61, 180, 242, 255), "W": (216, 243, 255, 255)}
    empty = {"K": (12, 12, 14, 190), "B": (26, 26, 30, 170), "W": (38, 38, 44, 170)}
    for y, row in enumerate(DROP_SHAPE):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            key = "K" if ch == "K" else "W" if ch == "W" else "B"
            use_full = kind == "full" or (kind == "half" and x <= 4)
            img.putpixel((x, y), (full if use_full else empty)[key])
    return img

def mark(img):
    """make the glyph advance equal its full width (+1) regardless of transparent edge columns"""
    img.putpixel((img.width - 1, 0), LAST_COL_MARKER)
    return img

def thirst_bar(level):
    """level 0..20 -> one 81x9 glyph. Icon i (0 = rightmost) is full when level >= 2i+2."""
    img = Image.new("RGBA", (BAR_W, 9), (0, 0, 0, 0))
    for i in range(10):
        x = (9 - i) * 8
        if level >= 2 * i + 2:
            kind = "full"
        elif level == 2 * i + 1:
            kind = "half"
        else:
            kind = "empty"
        tile = slot(kind)
        img.alpha_composite(tile, (x, 0))
    return mark(img)

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
    """level 0..4 -> 45x9 glyph: thermometer + 32px fill bar."""
    img = Image.new("RGBA", (TEMP_W, 9), (0, 0, 0, 0))
    colors = [
        [(46, 84, 214, 255), (154, 196, 255, 255)],   # freezing
        [(79, 195, 247, 255), (224, 247, 255, 255)],  # cold
        [(102, 187, 106, 255), (200, 241, 183, 255)], # mild
        [(255, 167, 38, 255), (255, 224, 178, 255)],  # warm
        [(229, 57, 53, 255), (255, 205, 210, 255)],   # hot
    ][level]
    main, hi = colors
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
    for y, row in enumerate(THERMO):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            col = main if ch == "M" else (36, 36, 38, 255) if ch == "K" else (207, 207, 207, 255)
            if ch == "G":
                col = hi if y <= 5 else main
            img.putpixel((x, y), col)
    return mark(img)

# -------------------------------------------------------------- assemble HUD atlases + font json
thirst_glyphs = [thirst_bar(v) for v in range(21)]          # E000..E014
THIRST_ROW1 = 11
atlas_t = Image.new("RGBA", (THIRST_ROW1 * BAR_W, 18), (0, 0, 0, 0))
for i, g in enumerate(thirst_glyphs):
    col, row = i % THIRST_ROW1, i // THIRST_ROW1
    atlas_t.paste(g, (col * BAR_W, row * 9))
atlas_t.save(os.path.join(ns, "textures/font/hud_thirst.png"))
chars_thirst = ["".join(chr(0xE000 + r * THIRST_ROW1 + c) for c in range(THIRST_ROW1 if r == 0 else 21 - THIRST_ROW1))
                for r in range(2)]
# the second row is shorter than the atlas is wide: pad with unused private-use chars so the grid is rectangular
chars_thirst[1] = chars_thirst[1] + "".join(chr(0xE030 + k) for k in range(THIRST_ROW1 - len(chars_thirst[1])))

atlas_p = Image.new("RGBA", (5 * TEMP_W, 9), (0, 0, 0, 0))
for v in range(5):
    atlas_p.paste(temp_bar(v), (v * TEMP_W, 0))
atlas_p.save(os.path.join(ns, "textures/font/hud_temp.png"))
chars_temp = ["".join(chr(0xE015 + v) for v in range(5))]

atlas_s = Image.new("RGBA", (4 * SEASON_W, 9), (0, 0, 0, 0))
for i, ic in enumerate([draw(SPRING, PAL_S), draw(SUMMER, PAL_S), draw(AUTUMN, PAL_S), draw(WINTER, PAL_S)]):
    atlas_s.paste(mark(ic), (i * SEASON_W, 0))
atlas_s.save(os.path.join(ns, "textures/font/hud_season.png"))
chars_season = ["".join(chr(0xE01A + i) for i in range(4))]

# invisible spacers come from a "space" provider: exact, can't be mis-measured like blank bitmaps
# (E020 = 9px, E021 = 3px, E022 = 1px)
# Bitmap glyphs need ascent <= height (the font loader rejects anything else), so the bars are moved
# with NEGATIVE ascents: the action bar line sits ~33px above the hunger row, and a bar with
# ascent -16 lands exactly on the row directly above the hunger icons.
font = {
    "providers": [
        {"type": "bitmap", "file": "homestead:font/hud_thirst.png", "ascent": ASCENT_THIRST, "height": 9, "chars": chars_thirst},
        {"type": "bitmap", "file": "homestead:font/hud_temp.png", "ascent": ASCENT_TEMP, "height": 9, "chars": chars_temp},
        {"type": "bitmap", "file": "homestead:font/hud_season.png", "ascent": ASCENT_TEMP, "height": 9, "chars": chars_season},
        {"type": "space", "advances": {"\ue020": 9, "\ue021": 3, "\ue022": 1}},
    ]
}
assert ASCENT_THIRST <= 9 and ASCENT_TEMP <= 9, "bitmap ascent must not exceed height (9)"
json.dump(font, open(os.path.join(ns, "font/hud.json"), "w"), indent=2)
for stale in ("textures/font/hud_space9.png", "textures/font/hud_space3.png"):
    p = os.path.join(ns, stale)
    if os.path.isfile(p):
        os.remove(p)

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
# backpack skins live in make_backpack.py (18-22 part models, 256px atlas)
# =================================================================================
import importlib.util
_spec = importlib.util.spec_from_file_location("make_backpack", os.path.join(os.path.dirname(os.path.abspath(__file__)), "make_backpack.py"))
_mb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mb)
_mb.main(pack)
print("homestead assets written")
