#!/usr/bin/env python3
"""
Generates custom_model_data overrides for Homestead items and writes them into an unzipped resource pack.

  python gen_pack.py <path/to/config.yml> <path/to/unzipped/pack>

Writes, for every base item used in config.yml (meal bases, PAPER for furniture):
  assets/minecraft/models/item/<base>.json   -> legacy "overrides" (Minecraft 1.21.1 - 1.21.3)
  assets/minecraft/items/<base>.json         -> range_dispatch on custom_model_data (1.21.4+)
Requires: pip install pyyaml
"""
import json, os, sys
from collections import defaultdict
import yaml

cfg_path, pack = sys.argv[1], sys.argv[2]
cfg = yaml.safe_load(open(cfg_path, encoding="utf-8"))

by_base = defaultdict(list)  # base item id -> [(cmd, model)]
for sec in (cfg.get("cooking", {}).get("recipes", {}), cfg.get("decor", {}).get("items", {}),
            cfg.get("wine", {}).get("types", {}),
            {**cfg.get("backpack", {}).get("skins", {}), "golden_leaf": cfg.get("artifacts", {}).get("golden-leaf", {})}):
    for _id, r in sec.items():
        cmd, model = r.get("cmd", 0), r.get("model", "")
        if not cmd or not model:
            continue
        base = r.get("base") or r.get("material") or "PAPER"
        by_base[base.lower()].append((int(cmd), model))

mc = os.path.join(pack, "assets", "minecraft")
os.makedirs(os.path.join(mc, "models", "item"), exist_ok=True)
os.makedirs(os.path.join(mc, "items"), exist_ok=True)

for base, entries in by_base.items():
    # legacy (<= 1.21.3)
    legacy = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"minecraft:item/{base}"},
        "overrides": [{"predicate": {"custom_model_data": c}, "model": m} for c, m in entries],
    }
    with open(os.path.join(mc, "models", "item", base + ".json"), "w", encoding="utf-8") as f:
        json.dump(legacy, f, indent=2)
    # modern (>= 1.21.4)
    modern = {"model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "fallback": {"type": "minecraft:model", "model": f"minecraft:item/{base}"},
        "entries": [{"threshold": c, "model": {"type": "minecraft:model", "model": m}} for c, m in entries],
    }}
    with open(os.path.join(mc, "items", base + ".json"), "w", encoding="utf-8") as f:
        json.dump(modern, f, indent=2)
    print(f"{base}: {len(entries)} override(s)")
