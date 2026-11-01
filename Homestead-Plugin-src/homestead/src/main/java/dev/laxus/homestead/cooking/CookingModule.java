package dev.laxus.homestead.cooking;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.db.Sqlite;
import dev.laxus.homestead.survival.SurvivalModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * Cooking pot = an empty CAULDRON on top of a LIT campfire.
 *  - Right-click with ingredients to add them (one at a time).
 *  - Right-click with a STICK to stir (starts cooking if the contents match a recipe).
 *  - When ready, right-click with the recipe's container (default BOWL) to serve.
 *  - Sneak + right-click behaves like a normal cauldron.
 */
public final class CookingModule extends AbstractModule {

    public record Recipe(String id, String name, int cmd, Material base, Material container,
                         Map<Material, Integer> ingredients, int seconds, int servings,
                         List<PotionEffect> effects, double thirst, int warmth) {}

    private record PotKey(UUID world, long pos) {}

    private static final class Pot {
        final Location loc;
        final Map<Material, Integer> contents = new EnumMap<>(Material.class);
        boolean cooking;
        Recipe ready;
        int servingsLeft;
        long readyAt;        // epoch millis when the running cook finishes (transient + stored for restarts)
        String pendingId;    // recipe id being cooked (has no result yet)

        Pot(Location loc) {
            this.loc = loc;
        }

        int total() {
            int n = 0;
            for (int v : contents.values()) n += v;
            return n;
        }
    }

    private static final int MAX_ITEMS = 12;

    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final Set<Material> knownIngredients = EnumSet.noneOf(Material.class);
    private final Map<PotKey, Pot> pots = new HashMap<>();
    private final NamespacedKey mealKey;

    public CookingModule(HomesteadPlugin plugin) {
        super(plugin);
        this.mealKey = new NamespacedKey(plugin, "meal");
    }

    @Override
    protected void onEnable() {
        loadRecipes();
        loadPots();
        every(10L, this::simmer);
    }

    @Override
    protected void onDisable() {
        // pots survive /reload and server restarts in SQLite instead of spilling their contents
        for (Pot pot : pots.values()) savePot(pot);
        pots.clear();
        recipes.clear();
        knownIngredients.clear();
    }

    // -------------------------------------------------------------- recipes

    private void loadRecipes() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("cooking.recipes");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) continue;

            Material base = Material.matchMaterial(r.getString("base", "BREAD"));
            Material container = Material.matchMaterial(r.getString("container", "BOWL"));
            ConfigurationSection ing = r.getConfigurationSection("ingredients");
            if (base == null || container == null || ing == null) {
                plugin.getLogger().warning("Cooking recipe '" + id + "' is invalid, skipping.");
                continue;
            }

            Map<Material, Integer> map = new EnumMap<>(Material.class);
            boolean bad = false;
            for (String k : ing.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    plugin.getLogger().warning("Recipe '" + id + "': unknown ingredient " + k);
                    bad = true;
                    break;
                }
                map.put(m, Math.max(1, ing.getInt(k)));
            }
            if (bad) continue;

            List<PotionEffect> effects = new ArrayList<>();
            for (String s : r.getStringList("effects")) {
                try {
                    String[] parts = s.split(":");
                    PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase()));
                    int secs = parts.length > 1 ? Integer.parseInt(parts[1]) : 10;
                    int amp = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                    if (type != null) effects.add(new PotionEffect(type, secs * 20, amp));
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("Recipe '" + id + "': bad effect '" + s + "'");
                }
            }

            recipes.put(id, new Recipe(id, r.getString("name", id), r.getInt("cmd", 0), base, container,
                    map, r.getInt("time", 8), Math.max(1, r.getInt("servings", 2)), effects,
                    r.getDouble("thirst", 0.0), r.getInt("warmth", 0)));
            knownIngredients.addAll(map.keySet());
        }
        plugin.getLogger().info("Loaded " + recipes.size() + " cooking recipe(s).");
    }

    public List<String> describeRecipes() {
        List<String> out = new ArrayList<>();
        for (Recipe r : recipes.values()) {
            StringBuilder sb = new StringBuilder(r.name()).append(" <gray>- ");
            boolean first = true;
            for (Map.Entry<Material, Integer> en : r.ingredients().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(en.getValue()).append("x ").append(nice(en.getKey()));
                first = false;
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Recipe rows for the recipe menu: [recipeId, ingredient summary]. */
    public List<String[]> menuEntries() {
        List<String[]> out = new ArrayList<>();
        for (Recipe r : recipes.values()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<Material, Integer> en : r.ingredients().entrySet()) {
                if (!first) sb.append(" + ");
                sb.append(en.getValue()).append("x ").append(nice(en.getKey()));
                first = false;
            }
            if (!r.effects().isEmpty()) sb.append(" \u2022 perks");
            if (r.thirst() > 0) sb.append(" \u2022 drink");
            if (r.warmth() > 0) sb.append(" \u2022 warmth");
            out.add(new String[]{r.id(), "<gray>" + sb});
        }
        return out;
    }

    public ItemStack createMeal(String id) {
        Recipe r = recipes.get(id);
        if (r == null) return null;
        ItemStack it = new ItemStack(r.base());
        ItemMeta m = it.getItemMeta();
        m.displayName(item(r.name()));
        if (r.cmd() > 0) m.setCustomModelData(r.cmd());
        // a cooked water bottle needs a vanilla base potion type or it is not drinkable
        if (r.base() == Material.POTION && m instanceof PotionMeta pm) pm.setBasePotionType(PotionType.WATER);
        m.getPersistentDataContainer().set(mealKey, PersistentDataType.STRING, r.id());
        it.setItemMeta(m);
        return it;
    }

    public Recipe recipeOf(String id) {
        return recipes.get(id);
    }

    /** The cooking-recipe id stored on a meal / must item, or null if it isn't one. */
    public String mealIdOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(mealKey, PersistentDataType.STRING);
    }

    /** Ids of every cooking recipe (for admin give + tab completion). */
    public List<String> recipeIds() {
        return new ArrayList<>(recipes.keySet());
    }

    // ----------------------------------------------------------------- pots

    private PotKey key(Block b) {
        return new PotKey(b.getWorld().getUID(), b.getBlockKey());
    }

    private boolean isPot(Block b) {
        if (b.getType() != Material.CAULDRON) return false;
        Block below = b.getRelative(BlockFace.DOWN);
        Material bm = below.getType();
        if (bm != Material.CAMPFIRE && bm != Material.SOUL_CAMPFIRE) return false;
        return below.getBlockData() instanceof Campfire cf && cf.isLit();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !isPot(b)) return;
        Player p = e.getPlayer();
        if (p.isSneaking()) return; // vanilla cauldron behaviour
        e.setCancelled(true);

        Pot pot = pots.computeIfAbsent(key(b), k -> new Pot(b.getLocation()));
        ItemStack hand = p.getInventory().getItemInMainHand();
        Material hm = hand.getType();

        if (pot.cooking) {
            p.sendActionBar(mm("<gold>⌛</gold> <gray>The pot is still simmering.</gray>"));
            return;
        }

        if (pot.ready != null) {
            if (hm == pot.ready.container() && !plugin.isHomesteadItem(hand)) {
                serve(p, pot, hand);
            } else {
                p.sendActionBar(mm("<green>✔ Ready! Right-click with a " + nice(pot.ready.container()) + " to serve."));
            }
            return;
        }

        if (hm == Material.AIR) {
            showContents(p, pot);
            return;
        }

        if (hm == Material.STICK) {
            startCooking(p, b, pot);
            return;
        }

        if (!knownIngredients.contains(hm) || plugin.isHomesteadItem(hand)) {
            p.sendActionBar(mm("<red>✖</red> <red>That doesn't belong in the pot.</red>"));
            return;
        }
        if (hm == Material.POTION && !isWaterBottle(hand)) {
            // Potion (the Material) means any potion - a brewed one would make a nasty stew
            p.sendActionBar(mm("<yellow>⚠</yellow> <yellow>The pot only boils plain water bottles.</yellow>"));
            return;
        }
        if (pot.total() >= MAX_ITEMS) {
            p.sendActionBar(mm("<red>⚠</red> <red>The pot is full, stir it with a stick.</red>"));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        pot.contents.merge(hm, 1, Integer::sum);
        savePot(pot);
        p.playSound(b.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.5f, 1.4f);
        p.sendActionBar(mm("<green>✚</green> " + nice(hm) + " <dark_gray>(" + pot.total() + "/" + MAX_ITEMS + ")"));
    }

    private void showContents(Player p, Pot pot) {
        if (pot.contents.isEmpty()) {
            p.sendActionBar(mm("<gray>♨ The pot is empty, add ingredients then stir with a stick."));
            return;
        }
        StringBuilder sb = new StringBuilder("<yellow>♨ In the pot: <white>");
        boolean first = true;
        for (Map.Entry<Material, Integer> en : pot.contents.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(en.getValue()).append("x ").append(nice(en.getKey()));
            first = false;
        }
        p.sendMessage(mm(sb.toString()));
    }

    private void startCooking(Player p, Block b, Pot pot) {
        Recipe match = null;
        for (Recipe r : recipes.values()) {
            if (r.ingredients().equals(pot.contents)) {
                match = r;
                break;
            }
        }
        if (match == null) {
            p.sendActionBar(mm(pot.contents.isEmpty()
                    ? "<gray>♨ Nothing to cook yet."
                    : "<red>✖</red> <red>No recipe matches these ingredients.</red> <dark_gray>(/homestead recipes)</dark_gray>"));
            return;
        }
        final Recipe recipe = match;
        pot.cooking = true;
        pot.pendingId = recipe.id();
        pot.readyAt = System.currentTimeMillis() + recipe.seconds() * 1000L;
        savePot(pot);
        p.playSound(b.getLocation(), Sound.ITEM_BUCKET_FILL, 0.6f, 0.8f);
        p.sendActionBar(mm("<gold>⌛</gold> <gold>Cooking " + recipe.name() + "...</gold>"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishCooking(pot, recipe), recipe.seconds() * 20L);
    }

    /** The cook timer ran out (fresh start or restored from SQLite): the recipe is ready to be served. */
    private void finishCooking(Pot pot, Recipe recipe) {
        if (!pots.containsValue(pot) || !pot.cooking) return;
        pot.cooking = false;
        pot.pendingId = null;
        pot.ready = recipe;
        pot.servingsLeft = recipe.servings();
        pot.loc.getWorld().playSound(pot.loc, Sound.BLOCK_BREWING_STAND_BREW, 1f, 0.7f);
        savePot(pot);
    }

    // ----------------------------------------------------------- pot storage

    private void savePot(Pot pot) {
        StringBuilder csv = new StringBuilder();
        for (Map.Entry<Material, Integer> en : pot.contents.entrySet()) {
            if (csv.length() > 0) csv.append(';');
            csv.append(en.getKey().name()).append(':').append(en.getValue());
        }
        String readyId = pot.cooking
                ? (pot.pendingId != null ? pot.pendingId : "")
                : (pot.ready != null ? pot.ready.id() : "");
        long readyAt = pot.cooking ? pot.readyAt : 0L;
        plugin.db().savePot(new Sqlite.PotRow(pot.loc.getWorld().getUID(), pot.loc.getBlockX(),
                pot.loc.getBlockY(), pot.loc.getBlockZ(), csv.toString(), pot.cooking, readyAt, readyId, pot.servingsLeft));
    }

    /** Restores pots saved before a restart; rows for pots that were removed are dropped. */
    private void loadPots() {
        int loaded = 0;
        for (Sqlite.PotRow row : plugin.db().loadPots()) {
            World w = Bukkit.getWorld(row.world());
            if (w == null) continue;   // world not loaded yet: the row waits for a later start
            Block b = w.getBlockAt(row.x(), row.y(), row.z());
            if (!isPot(b)) {
                plugin.db().deletePot(row);   // the pot was gone while the server was off
                continue;
            }
            Pot pot = new Pot(b.getLocation());
            for (String part : row.contents().split(";")) {
                String[] kv = part.split(":");
                Material m = kv.length == 2 ? Material.matchMaterial(kv[0]) : null;
                if (m == null) continue;
                try {
                    pot.contents.put(m, Integer.parseInt(kv[1]));
                } catch (NumberFormatException ignored) {
                }
            }
            pot.cooking = row.cooking();
            pot.servingsLeft = row.servings();
            pot.readyAt = row.readyAt();
            pot.pendingId = pot.cooking ? row.readyId() : null;
            Recipe recipe = pot.cooking ? null : recipes.get(row.readyId());
            pot.ready = recipe;
            if (!pot.cooking && recipe == null) pot.servingsLeft = 0;   // recipe was removed from the config
            pots.put(new PotKey(row.world(), b.getBlockKey()), pot);
            if (pot.cooking) {
                long delayTicks = Math.max(1, (pot.readyAt - System.currentTimeMillis()) / 50L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Recipe reopen = recipes.get(pot.pendingId);
                    if (reopen != null) {
                        finishCooking(pot, reopen);
                    } else {
                        pot.cooking = false;
                        pot.pendingId = null;
                        savePot(pot);   // recipe vanished from the config: leave the contents, they can be re-stirred
                    }
                }, delayTicks);
            }
            loaded++;
        }
        if (loaded > 0) plugin.getLogger().info("Restored " + loaded + " cooking pot(s).");
    }

    private void serve(Player p, Pot pot, ItemStack hand) {
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        ItemStack meal = createMeal(pot.ready.id());
        if (meal != null) {
            for (ItemStack left : p.getInventory().addItem(meal).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        plugin.quest(p, "COOK", pot.ready.id());
        pot.servingsLeft--;
        if (pot.servingsLeft <= 0) {
            pot.ready = null;
            pot.contents.clear();
        }
        savePot(pot);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        if (pot.servingsLeft <= 0) {
            p.sendActionBar(mm("<gray>♨ The pot is empty."));
        } else {
            p.sendActionBar(mm("<green>✔</green> <green>" + pot.servingsLeft + " serving(s) remaining.</green>"));
        }
    }

    private void simmer() {
        for (Pot pot : pots.values()) {
            if (!pot.cooking || pot.loc.getWorld() == null) continue;
            pot.loc.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    pot.loc.clone().add(0.5, 1.0, 0.5), 1, 0.15, 0.0, 0.15, 0.02);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        // breaking the campfire under a pot also ends that pot (no stale contents when a cauldron is placed again)
        if (b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) b = b.getRelative(BlockFace.UP);
        if (b.getType() != Material.CAULDRON) return;
        Pot pot = pots.remove(key(b));
        if (pot != null) dropContents(pot);
        plugin.db().deletePot(new Sqlite.PotRow(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(),
                "", false, 0L, "", 0));   // the pot and its saved row are gone together
    }

    private void dropContents(Pot pot) {
        if (pot.loc.getWorld() == null) return;
        if (pot.ready != null && pot.servingsLeft < pot.ready.servings()) {
            pot.contents.clear();   // already partly served: giving the raw ingredients back would duplicate food
            return;
        }
        for (Map.Entry<Material, Integer> en : pot.contents.entrySet()) {
            int left = en.getValue();
            while (left > 0) {
                int n = Math.min(left, en.getKey().getMaxStackSize());
                pot.loc.getWorld().dropItemNaturally(pot.loc.clone().add(0.5, 1.0, 0.5), new ItemStack(en.getKey(), n));
                left -= n;
            }
        }
        pot.contents.clear();
    }

    // ------------------------------------------------------------ eating

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemMeta m = e.getItem().getItemMeta();
        if (m == null) return;
        String id = m.getPersistentDataContainer().get(mealKey, PersistentDataType.STRING);
        if (id == null) return;
        Recipe r = recipes.get(id);
        if (r == null) return;

        Player p = e.getPlayer();
        for (PotionEffect pe : r.effects()) p.addPotionEffect(pe);
        SurvivalModule s = plugin.survival();
        if (s != null) {
            if (r.thirst() > 0) s.addThirst(p, r.thirst());
            if (r.warmth() > 0) s.warm(p, r.warmth());
        }
        plugin.quest(p, "EAT", r.id());
    }

    private static boolean isWaterBottle(ItemStack it) {
        return it.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() == PotionType.WATER;
    }

    private static String nice(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
