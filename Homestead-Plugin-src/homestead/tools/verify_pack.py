#!/usr/bin/env python3
"""
Sanity-checks the resource pack against config.yml so client versions can't drift apart.

  python tools/verify_pack.py <pack folder> <config.yml>

Fails (exit 1) when
  - an item's custom_model_data has no legacy override (1.20.5-1.21.3) or no 1.21.4+ items definition,
  - the two disagree about which model a custom_model_data value points to,
  - a Homestead model or font file is missing / references a missing texture,
  - a font provider is invalid (bitmap ascent > height, uneven chars rows, atlas size mismatch).
"""
import json, os, sys
import yaml
from PIL import Image

pack, cfg_path = sys.argv[1], sys.argv[2]
cfg = yaml.safe_load(open(cfg_path, encoding="utf-8"))
A = os.path.join(pack, "assets")
errors = []

def err(msg):
    errors.append(msg)

def mpath(m):
    ns, _, p = m.partition(":")
    if not p:
        ns, p = "minecraft", m
    return os.path.join(A, ns, "models", p + ".json")

def tpath(t):
    ns, _, p = t.partition(":")
    if not p:
        ns, p = "minecraft", t
    return os.path.join(A, ns, "textures", p + ".png")

# ---- 1) every configured custom model: legacy override + modern definition agree
entries = []
for k, v in cfg["decor"]["items"].items():
    entries.append((v["material"].lower(), v["cmd"], v["model"], "decor:" + k))
for k, v in cfg["backpack"]["skins"].items():
    entries.append((v["base"].lower(), v["cmd"], v["model"], "backpack:" + k))
for k, v in cfg["cooking"]["recipes"].items():
    entries.append((v["base"].lower(), v["cmd"], v["model"], "meal:" + k))
for k, v in cfg["wine"]["types"].items():
    entries.append((v["base"].lower(), v["cmd"], v["model"], "wine:" + k))
gl = cfg.get("artifacts", {}).get("golden-leaf")
if gl:
    entries.append((gl.get("base", "PAPER").lower(), gl["cmd"], gl["model"], "golden_leaf"))

vanilla_ns = ("minecraft",)
for base, cmd, model, label in entries:
    legacy = os.path.join(A, "minecraft", "models", "item", base + ".json")
    modern = os.path.join(A, "minecraft", "items", base + ".json")
    if not os.path.isfile(legacy):
        err(f"{label}: missing legacy model assets/minecraft/models/item/{base}.json")
    else:
        ov = {o["predicate"].get("custom_model_data"): o["model"] for o in json.load(open(legacy)).get("overrides", [])}
        if ov.get(cmd) != model:
            err(f"{label}: legacy override for cmd {cmd} is {ov.get(cmd)!r}, expected {model!r}")
    if not os.path.isfile(modern):
        err(f"{label}: missing 1.21.4+ definition assets/minecraft/items/{base}.json")
    else:
        ents = {e["threshold"]: e["model"]["model"] for e in json.load(open(modern))["model"]["entries"]}
        if ents.get(cmd) != model:
            err(f"{label}: items/{base}.json for cmd {cmd} is {ents.get(cmd)!r}, expected {model!r}")
    ns = model.split(":")[0]
    if ns not in vanilla_ns and not os.path.isfile(mpath(model)):
        err(f"{label}: model file missing for {model}")

# ---- 2) models in our namespaces: textures exist
for ns in os.listdir(A):
    mdir = os.path.join(A, ns, "models")
    if ns == "minecraft" or not os.path.isdir(mdir):
        continue
    for root, _, files in os.walk(mdir):
        for f in files:
            if not f.endswith(".json"):
                continue
            j = json.load(open(os.path.join(root, f)))
            for name, t in (j.get("textures") or {}).items():
                if t.startswith("#"):
                    continue
                if t.split(":")[0] == "minecraft" or ":" not in t and False:
                    continue
                if not os.path.isfile(tpath(t)):
                    err(f"{os.path.relpath(os.path.join(root, f), A)}: texture {t} not found")

# ---- 3) HUD font validity
fp = os.path.join(A, "homestead", "font", "hud.json")
if os.path.isfile(fp):
    for prov in json.load(open(fp))["providers"]:
        if prov["type"] == "bitmap":
            if prov["ascent"] > prov["height"]:
                err(f"hud.json: ascent {prov['ascent']} > height {prov['height']} in {prov['file']} (rejected by the client)")
            rows = prov["chars"]
            if len({len(r) for r in rows}) != 1:
                err(f"hud.json: chars rows of {prov['file']} have different lengths")
            else:
                f = tpath(prov["file"].replace("font/", "font/", 1)) if False else os.path.join(
                    A, prov["file"].split(":")[0], "textures", prov["file"].split(":")[1])
                if not os.path.isfile(f):
                    err(f"hud.json: {prov['file']} missing")
                else:
                    w, h = Image.open(f).size
                    if w % len(rows[0]) or h % len(rows):
                        err(f"hud.json: {prov['file']} is {w}x{h}, not divisible into {len(rows[0])}x{len(rows)} cells")

mc = json.load(open(os.path.join(pack, "pack.mcmeta")))
if "pack" not in mc:
    err("pack.mcmeta has no 'pack' section")

if errors:
    print("PACK CHECK FAILED")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print(f"pack OK: {len(entries)} custom models consistent across legacy + 1.21.4+, HUD font valid")
