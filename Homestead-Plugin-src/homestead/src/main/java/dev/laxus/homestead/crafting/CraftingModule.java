package dev.laxus.homestead.crafting;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.cooking.CookingModule;
import dev.laxus.homestead.decor.DecorModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crafting-table recipes for everything that isn't cooked or brewed.
 *
 * By default every furniture item and the Golden Leaf get a sensible default
 * recipe (see DEFAULTS). Anything can be overridden or added in config.yml:
 *
 *   crafting:
 *     recipes:
 *       oak_chair:            # key doubles as the furniture id
 *         shape: ["WW", "SS"]
 *         ingredients: { W: OAK_PLANKS, S: STICK }
 *       my_recipe:
 *         shapeless: [DIAMOND, ECHO_SHARD]
 *         result: "decor:oak_cabinet"   # also: "backpack", "backpack:<skin>", "golden_leaf"
 */
public final class CraftingModule extends AbstractModule {

    private final List<NamespacedKey> keys = new ArrayList<>();
    private final Map<String, String> byKey = new HashMap<>(); // recipe key -> result id (for quest logs)
    private final Map<String, String> wineChecks = new HashMap<>(); // wine recipe key -> must id its ingredients must be

    public CraftingModule(HomesteadPlugin plugin) {
        super(plugin);
    }

    private static final Map<String, String> PLANKS = Map.of(
            "oak", "OAK_PLANKS", "spruce", "SPRUCE_PLANKS", "birch", "BIRCH_PLANKS",
            "dark_oak", "DARK_OAK_PLANKS", "cherry", "CHERRY_PLANKS", "bamboo", "BAMBOO_PLANKS");

    /** furniture id -> default recipe: either a shaped pattern + ingredient map, or a shapeless list. */
    private static final Map<String, RecipeSpec> DEFAULTS = buildDefaults();

    private record RecipeSpec(String[] shape, Map<Character, String> ingredients, List<String> shapeless) {}

    private static RecipeSpec shaped(String[] shape, String... pairs) {
        Map<Character, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i].charAt(0), pairs[i + 1]);
        return new RecipeSpec(shape, m, null);
    }

    private static RecipeSpec shapeless(String... mats) {
        return new RecipeSpec(null, null, List.of(mats));
    }

    private static Map<String, RecipeSpec> buildDefaults() {
        Map<String, RecipeSpec> d = new LinkedHashMap<>();
        for (String wood : new String[]{"oak", "spruce", "birch", "dark_oak", "cherry"}) {
            String plank = PLANKS.get(wood);
            d.put(wood + "_chair", shaped(new String[]{"WW", "SS"}, "W", plank, "S", "STICK"));
            d.put(wood + "_cabinet", shaped(new String[]{"PPP", "PCP", "PPP"}, "P", plank, "C", "CHEST"));
        }
        d.put("straw_stool", shaped(new String[]{"WWW", "W W", "WWW"}, "W", "WHEAT"));
        d.put("tatami_mat", shaped(new String[]{"PPP", "PDP", "PPP"}, "P", "PAPER", "D", "GREEN_DYE"));
        d.put("canvas_rug", shaped(new String[]{"WW", "WW"}, "W", "WHITE_WOOL"));
        d.put("cooking_pot", shaped(new String[]{"III", "IBI", "III"}, "I", "IRON_INGOT", "B", "BUCKET"));
        d.put("stove", shaped(new String[]{"III", "CFC", "CCC"}, "I", "IRON_INGOT", "C", "COBBLESTONE", "F", "FURNACE"));
        d.put("rice_bale", shaped(new String[]{"WWW", "WSW", "WWW"}, "W", "WHEAT", "S", "STICK"));
        d.put("wooden_basket", shaped(new String[]{"W W", "WSW", "W W"}, "W", "BIRCH_PLANKS", "S", "STICK"));
        d.put("bamboo_basket", shaped(new String[]{"W W", "WSW", "W W"}, "W", "BAMBOO_PLANKS", "S", "STICK"));
        d.put("cutting_board", shaped(new String[]{"PPP", "PPP"}, "P", "OAK_PLANKS"));
        d.put("golden_leaf", shapeless("GOLD_NUGGET", "GOLD_NUGGET", "GOLD_NUGGET", "GOLD_NUGGET", "APPLE"));
        return d;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onEnable() {
        ConfigurationSection custom = plugin.getConfig().getConfigurationSection("crafting.recipes");

        // default recipes for every furniture item unless overridden in config
        DecorModule decor = plugin.decor();
        if (decor != null && plugin.getConfig().getBoolean("crafting.auto-decor", true)) {
            for (String id : decor.ids()) {
                boolean hasCustom = custom != null && custom.contains(id);
                if (!hasCustom) {
                    RecipeSpec spec = DEFAULTS.get(id);
                    if (spec == null) {
                        plugin.getLogger().info("Crafting: no recipe for '" + id + "' (add one under crafting.recipes).");
                        continue;
                    }
                    register("craft_" + id, id, spec, 1);
                }
            }
        }

        // golden leaf default + configurable customs
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                ConfigurationSection r = custom.getConfigurationSection(key);
                if (r == null) continue;
                String result = r.getString("result", key);
                int amount = Math.max(1, r.getInt("amount", 1));
                List<String> shapeList = r.getStringList("shape");
                List<String> mats = r.getStringList("shapeless");

                RecipeSpec spec;
                if (!mats.isEmpty()) {
                    spec = shapeless(mats.toArray(new String[0]));
                } else if (!shapeList.isEmpty()) {
                    Map<Character, String> ing = new LinkedHashMap<>();
                    ConfigurationSection is = r.getConfigurationSection("ingredients");
                    if (is != null) {
                        for (String k : is.getKeys(false)) {
                            if (k.length() == 1) ing.put(k.charAt(0), is.getString(k, "STONE"));
                        }
                    }
                    spec = new RecipeSpec(shapeList.toArray(new String[0]), ing, null);
                } else {
                    plugin.getLogger().warning("Crafting recipe '" + key + "': needs 'shape' or 'shapeless'.");
                    continue;
                }
                register("craft_" + key.toLowerCase(Locale.ROOT), result, spec, amount);
            }
        }

        // the golden leaf has no decor entry - register its default unless a custom recipe took over
        if (!keys.contains(new NamespacedKey(plugin, "craft_golden_leaf"))
                && (custom == null || !custom.contains("golden_leaf"))
                && plugin.getConfig().getBoolean("crafting.auto-decor", true)) {
            register("craft_golden_leaf", "golden_leaf", DEFAULTS.get("golden_leaf"), 1);
        }

        registerCookedItems(custom);

        for (Player p : Bukkit.getOnlinePlayers()) {
            for (NamespacedKey k : keys) p.discoverRecipe(k);
        }
        plugin.getLogger().info("Crafting: registered " + keys.size() + " recipe(s).");
    }

    @Override
    protected void onDisable() {
        for (NamespacedKey k : keys) Bukkit.removeRecipe(k);
        keys.clear();
        byKey.clear();
        wineChecks.clear();
    }

    /**
     * Crafting-table recipes for the cooked items, so every Homestead item can be made by hand:
     *  - meals / must: the pot's ingredients plus its container, shapeless, ONE serving
     *    (the pot makes several servings from the same ingredients, so it stays the better deal)
     *  - wine: three must bottles (checked by tag, not just by material) - a costly shortcut
     *    around the barrel. Toggle with crafting.auto-meals / crafting.auto-wine.
     */
    private void registerCookedItems(ConfigurationSection custom) {
        CookingModule cook = plugin.cooking();
        if (cook == null) return;
        if (plugin.getConfig().getBoolean("crafting.auto-meals", true)) {
            for (String id : cook.recipeIds()) {
                CookingModule.Recipe r = cook.recipeOf(id);
                if (r == null || (custom != null && custom.contains(id))) continue;
                List<String> mats = new ArrayList<>();
                for (Map.Entry<Material, Integer> en : r.ingredients().entrySet()) {
                    for (int i = 0; i < en.getValue(); i++) mats.add(en.getKey().name());
                }
                mats.add((r.container() == null ? Material.BOWL : r.container()).name());
                if (mats.size() > 9) {
                    plugin.getLogger().info("Crafting: '" + id + "' needs more than 9 ingredients, pot only.");
                    continue;
                }
                register("craft_meal_" + id, "meal:" + id, shapeless(mats.toArray(new String[0])), 1);
            }
        }
        if (plugin.wine() != null && plugin.getConfig().getBoolean("crafting.auto-wine", true)) {
            for (String wid : plugin.wine().ids()) {
                String mustId = plugin.wine().mustOf(wid);
                CookingModule.Recipe must = mustId == null ? null : cook.recipeOf(mustId);
                if (must == null || (custom != null && custom.contains(wid))) continue;
                String m = must.base().name();
                String keyName = "craft_wine_" + wid;
                register(keyName, "wine:" + wid, shapeless(m, m, m), 1);
                wineChecks.put(keyName, mustId);
            }
        }
    }

    /**
     * Custom items wear a vanilla base material (PAPER, LEATHER, GOLD_NUGGET, BREAD...), so they must never be
     * eaten as plain ingredients by other recipes (a backpack would vanish with its contents).
     * Only the wine recipes (must bottles) and the backpack skin recipes may take custom items.
     * Wine recipes also only accept real must bottles (a plain honey bottle must not match).
     */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof org.bukkit.Keyed k)) return;
        String name = k.getKey().getKey();
        boolean ours = k.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT));
        boolean allowCustom = ours && (wineChecks.containsKey(name) || name.startsWith("backpack_skin_"));
        if (!allowCustom) {
            for (ItemStack it : e.getInventory().getMatrix()) {
                if (plugin.isHomesteadItem(it)) {
                    e.getInventory().setResult(null);
                    return;
                }
            }
            return;
        }
        String must = wineChecks.get(name);
        if (must == null || plugin.cooking() == null) return;
        int ok = 0;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || it.getType().isAir()) continue;
            if (!must.equals(plugin.cooking().mealIdOf(it))) {
                e.getInventory().setResult(null);
                return;
            }
            ok++;
        }
        if (ok != 3) e.getInventory().setResult(null);
    }

    private void register(String keyName, String result, RecipeSpec spec, int amount) {
        NamespacedKey key = new NamespacedKey(plugin, keyName);
        ItemStack out = resolveResult(result);
        if (out == null) {
            plugin.getLogger().warning("Crafting recipe '" + keyName + "': unknown result '" + result + "'");
            return;
        }
        if (amount > 1) out.setAmount(amount);
        try {
            addRecipe(key, out, spec, keyName);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            plugin.getLogger().warning("Crafting recipe '" + keyName + "' is invalid: " + ex.getMessage());
            return;
        }
        keys.add(key);
        byKey.put(key.getKey(), result);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(key);
    }

    private void addRecipe(NamespacedKey key, ItemStack out, RecipeSpec spec, String keyName) {
        if (spec.shapeless() != null) {
            ShapelessRecipe r = new ShapelessRecipe(key, out);
            for (String m : spec.shapeless()) {
                Material mat = Material.matchMaterial(m);
                if (mat == null) {
                    throw new IllegalArgumentException("unknown material " + m);
                }
                r.addIngredient(new RecipeChoice.MaterialChoice(mat));
            }
            if (!Bukkit.addRecipe(r)) throw new IllegalStateException("could not be registered");
        } else {
            ShapedRecipe r = new ShapedRecipe(key, out);
            r.shape(spec.shape());
            for (Map.Entry<Character, String> en : spec.ingredients().entrySet()) {
                Material mat = Material.matchMaterial(en.getValue());
                if (mat == null) {
                    throw new IllegalArgumentException("unknown material " + en.getValue());
                }
                r.setIngredient(en.getKey(), new RecipeChoice.MaterialChoice(mat));
            }
            if (!Bukkit.addRecipe(r)) throw new IllegalStateException("could not be registered");
        }
    }

    /** result forms: "decor:<id>", "<furniture id>", "meal:<id>", "wine:<id>", "backpack", "backpack:<skin>", "golden_leaf". */
    private ItemStack resolveResult(String result) {
        if (result.equalsIgnoreCase("golden_leaf") || result.equalsIgnoreCase("golden-leaf")) {
            return plugin.artifacts() != null ? plugin.artifacts().createGoldenLeaf() : null;
        }
        if (result.toLowerCase(Locale.ROOT).startsWith("meal:")) {
            return plugin.cooking() == null ? null : plugin.cooking().createMeal(result.substring(5));
        }
        if (result.toLowerCase(Locale.ROOT).startsWith("wine:")) {
            return plugin.wine() == null ? null : plugin.wine().createWine(result.substring(5));
        }
        if (result.toLowerCase(Locale.ROOT).startsWith("decor:")) {
            DecorModule d = plugin.decor();
            return d == null ? null : d.createItem(result.substring(6));
        }
        if (result.toLowerCase(Locale.ROOT).startsWith("backpack")) {
            if (plugin.backpack() == null) return null;
            int i = result.indexOf(':');
            String skin = i > 0 ? result.substring(i + 1) : null;
            return skin == null ? plugin.backpack().createBackpack() : plugin.backpack().createBackpack(skin);
        }
        DecorModule d = plugin.decor();
        return d == null ? null : d.createItem(result);
    }

    public List<Recipe> registered() {
        List<Recipe> out = new ArrayList<>();
        for (NamespacedKey k : keys) out.add(Bukkit.getRecipe(k));
        return out;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (NamespacedKey k : keys) e.getPlayer().discoverRecipe(k);
    }
}
