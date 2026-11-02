# Homestead (Paper plugin)

Cozy "Vanilla+" survival inspired by the Homestead modpack, as a server-side plugin.
Pairs with your **Realism** plugin and the **Realism + Farmer's Delight resource pack**.

Requires Paper **26.3+** and Java 25. Custom items use `custom_model_data`; the resource pack needs the generated overrides (see below).

## Modules (toggle in config.yml → `modules`)
| Module | What it does |
|---|---|
| survival | 4 seasons, effective temperature (cold overlay, warm near fires / in leather / after hot meals), thirst bar (full bar drains to 0 in 20 minutes; unboiled water can poison you - boil it in the pot), drink from water (sneak + right-click, empty hand) |
| farming | crop growth = season % × biome % (100 / 50 / 0 each, set per crop in `config.yml`); bone meal obeys it; `/homestead crop` or sneak + right-click shows why |
| cooking | cauldron on a lit campfire = cooking pot. Add ingredients, stir with a stick, serve with a bowl. Meals use Farmer's Delight models from the pack |
| quests | chains in `quests.yml` (BREAK, HARVEST, KILL, CRAFT, COOK, EAT, DRINK, DECOR), item/command/xp rewards, `/quests` |
| decor | 5 chairs, stool, tatami mat, rug, 4 cabinets, pot, stove, baskets, rice bale, cutting board (ItemDisplay + Interaction). Right-click the top of a block to place, right-click to sit (clicking the block the furniture stands on also works), sneak + right-click to pick up. Breaking the support block drops it back |
| backpack | a wearable textured chestplate (chainmail base, flipped: it wipes the vanilla armour bonus). One item = one unique id, contents live inside the item. Craft with 7 leather + string + chest (`LSL/LCL/LLL`); dye (brown, red, black, orange, green) to re-skin a backpack + its 5 skins (classic, explorer, shadow, camper, forest). Open: right-click with the pack in hand, or sneak + right-click with an empty hand while wearing. Wearing it in the chest slot shows the detailed 17-20 part 3D model (256px texture: leather grain, stitching, brass buckles, bottle pockets, bedroll/top roll per skin) on your back, visible to you on F5 and to everyone else (it drops out of first person when you look steeply down) |
| artifacts | Golden Leaf: rare drop from leaves (3x more in autumn). Hold it for Speed + Luck |
| wine | cook a must, put the bottles in a barrel, wait - they turn into wine inside the barrel, with a live progress bar and countdown on the action bar. Drinking gives Nausea (stacks if you drink fast) |
| crafting | crafting-table recipes for every furniture piece + the Golden Leaf; add your own in `crafting.recipes` |

## Crafting
Every furniture item and the Golden Leaf are craftable out of the box (e.g. chair = 2 planks over 2 sticks, cabinet = planks around a chest). Defaults are built from `decor.items`; disable with `crafting.auto-decor: false` or override any recipe in config:
```yaml
crafting:
  recipes:
    oak_chair:                  # key = furniture id, "result" optional
      shape: ["WW", "SS"]
      ingredients: { W: OAK_PLANKS, S: STICK }
    my_extra:
      shapeless: [DIAMOND, ECHO_SHARD]
      result: "backpack:forest"  # decor:<id> | backpack | backpack:<skin> | golden_leaf
```
Meals stay cooked in the pot - they are intentionally not crafting-table recipes.

## Wine
1. Cook a must in the pot: berry (6 sweet berries + sugar), apple (4 apples + sugar) or glow (6 glow berries + sugar). Serve with glass bottles.
2. **Open a barrel and put the must bottles inside** - no special right-click needed, the barrel stays fully vanilla.
3. The timer starts the moment the bottles go in (click, drag and shift-click are all detected) and counts down live on the action bar (progress bar, percent, time left) while the barrel is open. It keeps running when you close it: fermenting barrels in loaded chunks are tracked and finish on their own, with a few bubbles rising from them. After `wine.ferment-ticks` (default 10 min) the bottles turn into wine **right inside the barrel** - just pick them up.

## HUD
Icon style uses the `homestead:hud` font from the resource pack: a thirst bar (21 levels, exactly as wide as the hunger bar) sits pixel-locked in the row **directly above the hunger icons**, and the temperature bar + season icon sit on the **right** of that row. The action bar is anchored to the bottom of the screen, so a true top-right corner is not possible without a shader; raise `hud.temp-right` to push them further right. `/quests` and `/homestead recipes` now open proper inventory menus.
Nudge positions in config (then regenerate the pack, see below):
- `hud.temp-right` - px from screen centre to the right edge of the temperature bar (min 140)
- `hud.thirst-ascent` / `hud.temp-ascent` - vertical offsets baked into the pack font (-16 = the row above hunger); must be <= 9 or clients reject the font. Rebuild the pack after changing them
`hud.style: text` for a plain text bar, `hud.labels: true` to add words next to the icons.

## Commands
- `/quests [all]` (opens the quest menu; `all` keeps the chat list)
- `/homestead season | recipes` (recipes opens the recipe book menu)
- `/homestead give <player> <furniture_id|backpack|golden_leaf> [amount]` (homestead.admin)
- `/homestead reload` (homestead.admin)

## Build
```
mvn clean package
```
Jar: `target/Homestead.jar` → drop in `plugins/`. Keep the Realism plugin too (soft-depend).
CI builds with `-Dhomestead.version=0.1.<run number>` so every run bumps the plugin version (local builds stay `0.1.0`); `plugin.yml` gets it via resource filtering.

## Resource pack
Use **Realism-ResourcePack-Homestead-v6.zip** (your pack + new HUD glyph bars + 256px detailed backpack skins + wine, must and Golden Leaf art + model overrides). `pack.mcmeta` covers 1.20.1 → latest (`pack_format: 15` + `supported_formats` up to 99999) so ViaVersion clients of any version accept it.
If you change `cmd`/`model`/HUD values in config.yml, regenerate:
```
pip install pyyaml pillow
python tools/gen_pack.py src/main/resources/config.yml <unzipped pack folder>
python tools/make_assets.py <unzipped pack folder> src/main/resources/config.yml   # HUD glyphs + backpack art (make_backpack.py)
python tools/gen_pack.py src/main/resources/config.yml <unzipped pack folder>      # custom_model_data overrides (legacy + 1.21.4+)
python tools/verify_pack.py <unzipped pack folder> src/main/resources/config.yml   # consistency check, run in CI
```
Then re-zip the pack (pack.mcmeta must be at the zip root) and update the SHA-1 in server.properties.

## Storage - SQLite
All plugin state lives in one SQLite file: `plugins/Homestead/data/homestead.db` (Paper bundles the SQLite driver, no extra dependency).
- **Quest progress** (per player). An old `quest-data.yml` is imported automatically on first start and renamed `.imported`.
- **Cooking pots**: contents, cooking state and servings survive `/reload` and server restarts; a pot that was physically removed while the server was off is cleaned up. Half-finished cooking timers resume from the stored finish time.
- Furniture, wine barrels and backpack contents are not in the database on purpose: they belong to entities, block states and item NBT, which persist on their own.

## Backpack on your back

`backpack.worn-mode: equipment` (default) gives every backpack an `equippable` component (chest slot) with its own equipment asset `homestead:backpack_<skin>`. The client draws it as an armour layer, so it shows in F5 and is never drawn in first person, exactly like a chestplate. It is painted on the chestplate body layout (pack on the back, straps on the chest and shoulders) by `tools/make_equipment.py`; old backpacks are upgraded automatically on join / when worn.

`backpack.worn-mode: display` brings back the legacy 3D ItemDisplay on your back (tune `backpack.back.*`, `hide-when-looking-down`, `hide-from-self`).

## Tuning notes
- Sitting height differs per model: adjust `seat-height` per furniture in config. If a model renders too low/high, use its `display-y`.
- Furniture is not protected by land-claim plugins; placement doesn't fire BlockPlaceEvent.
- Cooking pot contents live in memory (dropped on reload/shutdown).

## Furniture hitboxes (ghost-block fix)
Each piece is an ItemDisplay (the model, lifted so it stands on the floor) plus an Interaction (the hitbox, spawned at the floor so it covers the model). Both are linked and removed together on pick-up. Furniture placed by older versions is migrated automatically when its chunk loads, and orphaned halves are removed.

## Crafting
Every item can be made by hand: furniture, backpacks (+ dye re-skins), Golden Leaf, and - with `crafting.auto-meals` / `crafting.auto-wine` - cooked meals and must (pot ingredients + container, 1 serving) and wine (3 must bottles, a costly shortcut around the barrel).
