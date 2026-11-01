package dev.laxus.homestead.wine;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.survival.SurvivalModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Barrel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * Wine making:
 *  1. Cook a "must" (fruit + sugar) in the cooking pot - it comes out in glass bottles.
 *  2. Just put must bottles INTO a vanilla barrel - no special right-click ritual.
 *     The timer starts when the bottles are put in and is reported on every peek.
 *  3. After ferment-ticks the bottles turn into wine automatically, inside the barrel.
 *  Drinking wine gives Nausea, stacking if you keep drinking.
 */
public final class WineModule extends AbstractModule {

    public record WineType(String id, String from, String name, Material base, int cmd, int nausea,
                           int warmth, List<PotionEffect> effects) {}

    private final Map<String, WineType> types = new LinkedHashMap<>();
    private final Map<String, WineType> byMust = new HashMap<>();
    private final Map<UUID, Double> drunk = new HashMap<>();
    private final Map<UUID, Long> drunkAt = new HashMap<>();

    private final NamespacedKey mealKey;   // shared with the cooking module (must is a cooked "meal")
    private final NamespacedKey wineKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey startKey;

    public WineModule(HomesteadPlugin plugin) {
        super(plugin);
        mealKey = new NamespacedKey(plugin, "meal");
        wineKey = new NamespacedKey(plugin, "wine");
        typeKey = new NamespacedKey(plugin, "ferment_type");
        startKey = new NamespacedKey(plugin, "ferment_start");
    }

    @Override
    protected void onEnable() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("wine.types");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Material base = Material.matchMaterial(s.getString("base", "HONEY_BOTTLE"));
                String from = s.getString("from", "");
                if (base == null || from.isEmpty()) {
                    plugin.getLogger().warning("Wine '" + id + "' is invalid, skipping.");
                    continue;
                }
                List<PotionEffect> effects = new ArrayList<>();
                for (String e : s.getStringList("effects")) {
                    try {
                        String[] parts = e.split(":");
                        PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase()));
                        int secs = parts.length > 1 ? Integer.parseInt(parts[1]) : 10;
                        int amp = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                        if (type != null) effects.add(new PotionEffect(type, secs * 20, amp));
                    } catch (NumberFormatException ex) {
                        plugin.getLogger().warning("Wine '" + id + "': bad effect '" + e + "'");
                    }
                }
                WineType t = new WineType(id, from, s.getString("name", id), base, s.getInt("cmd", 0),
                        s.getInt("nausea", 10), s.getInt("warmth", 0), effects);
                types.put(id, t);
                byMust.put(from, t);
            }
        }
        plugin.getLogger().info("Loaded " + types.size() + " wine type(s).");
        every(100L, this::scanOpenBarrels);
    }

    @Override
    protected void onDisable() {
        types.clear();
        byMust.clear();
        drunk.clear();
        drunkAt.clear();
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (WineType t : types.values()) out.add(t.name() + " <gray>- ferment <white>" + t.from());
        return out;
    }

    /** Ids of every wine type (for admin give + tab completion). */
    public List<String> ids() {
        return new ArrayList<>(types.keySet());
    }

    public ItemStack createWine(String id) {
        WineType t = types.get(id);
        if (t == null) return null;
        ItemStack it = new ItemStack(t.base());
        ItemMeta m = it.getItemMeta();
        m.displayName(item(t.name()));
        m.lore(List.of(item("<gray>Aged in a barrel"), item("<dark_gray>Drink responsibly")));
        if (t.cmd() > 0) m.setCustomModelData(t.cmd());
        m.getPersistentDataContainer().set(wineKey, PersistentDataType.STRING, t.id());
        it.setItemMeta(m);
        return it;
    }

    private String mealId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(mealKey, PersistentDataType.STRING);
    }

    // --------------------------------------------------------------- barrel

    /*
     * Barrels behave fully like vanilla barrels - no controlled right-clicking.
     * Just open one and put must bottles inside. The plugin scans the barrel
     * (on close + while it stays open + on interact) and, once ferment-ticks
     * have passed, converts the must bottles into wine bottles right in place.
     */

    /** Reports the timer when the player opens a barrel with must inside. */
    @EventHandler
    public void onBarrelOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (!(barrel.getBlock().getType() == Material.BARREL) || !plugin.worldAllowed(barrel.getWorld())) return;

        long ferment = plugin.getConfig().getLong("wine.ferment-ticks", 12000L);
        long start = barrel.getPersistentDataContainer().getOrDefault(startKey, PersistentDataType.LONG, -1L);
        if (start < 0) return;   // not fermenting yet (scan sets it)
        WineType t = types.get(barrel.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING));
        if (t == null) return;
        int count = countMust(barrel);
        if (count == 0) return;
        long left = ferment - (barrel.getWorld().getGameTime() - start);
        if (left <= 0) {
            p.sendActionBar(mm("<green>" + t.name() + "<green> is ready!"));
        } else {
            long minutes = (left + 1199) / 1200;
            p.sendActionBar(mm("<gold>" + count + "x " + t.name() + "<gold> - about " + minutes + " min left."));
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (barrel.getBlock().getType() != Material.BARREL) return;
        scan(barrel, false);
    }

    /** Keeps barrels that players leave open ticking along. */
    private void scanOpenBarrels() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getType() != org.bukkit.event.inventory.InventoryType.BARREL) continue;
            if (top.getHolder() instanceof Barrel barrel && barrel.getBlock().getType() == Material.BARREL) {
                scan(barrel, false);
            }
        }
    }

    /** One scan: refresh the ferment state of a barrel, converting must bottles when ready. */
    private void scan(Barrel barrel, boolean quiet) {
        org.bukkit.inventory.Inventory inv = barrel.getInventory();
        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        String stored = pdc.get(typeKey, PersistentDataType.STRING);
        long start = pdc.getOrDefault(startKey, PersistentDataType.LONG, -1L);

        int count = 0;
        WineType found = null;
        for (ItemStack it : inv.getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            if (it.getItemMeta().getPersistentDataContainer().has(wineKey, PersistentDataType.STRING)) continue;
            String mid = mealId(it);
            if (mid == null) continue;
            WineType wt = byMust.get(mid);
            if (wt == null) continue;
            if (found == null) found = wt;
            count += it.getAmount();
        }

        if (count == 0) {
            if (stored != null || start >= 0) {
                pdc.remove(typeKey);
                pdc.remove(startKey);
                barrel.update();
            }
            return;
        }

        if (stored == null || !stored.equals(found.id())) {
            // a new batch of must was put in - timer starts now
            pdc.set(typeKey, PersistentDataType.STRING, found.id());
            pdc.set(startKey, PersistentDataType.LONG, barrel.getWorld().getGameTime());
            barrel.update();
            return;
        }

        long ferment = plugin.getConfig().getLong("wine.ferment-ticks", 12000L);
        long elapsed = barrel.getWorld().getGameTime() - start;
        if (elapsed >= ferment) {
            // ready: turn every must bottle into wine, right where it sits
            ItemStack[] contents = inv.getContents();
            int converted = 0;
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || !it.hasItemMeta()) continue;
                String mid = it.getItemMeta().getPersistentDataContainer().get(mealKey, PersistentDataType.STRING);
                WineType wt = mid == null ? null : byMust.get(mid);
                if (wt == null) continue;
                ItemStack wine = createWine(wt.id());
                wine.setAmount(it.getAmount());
                contents[i] = wine;
                converted += it.getAmount();
            }
            if (converted > 0) {
                inv.setContents(contents);
                pdc.remove(typeKey);
                pdc.remove(startKey);
                barrel.update();
                barrel.getWorld().playSound(barrel.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.2f);
                for (Player p : barrel.getWorld().getPlayers()) {
                    if (p.getWorld().equals(barrel.getWorld()) && p.getLocation().distanceSquared(barrel.getLocation()) < 256) {
                        p.sendActionBar(mm("<green>The wine has finished fermenting."));
                    }
                }
                if (plugin.quests() != null) {
                    for (Player p : barrel.getWorld().getPlayers()) {
                        if (p.getWorld().equals(barrel.getWorld())) {
                            plugin.quests().progress(p, "BREW", found.id(), converted);
                            break;
                        }
                    }
                }
            }
        } else {
            barrel.update();   // persist nothing new, just in case the state object is dirty
        }
    }

    /** Number of must bottles currently sitting in the barrel. */
    private int countMust(Barrel barrel) {
        int n = 0;
        for (ItemStack it : barrel.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            if (it.getItemMeta().getPersistentDataContainer().has(wineKey, PersistentDataType.STRING)) continue;
            String mid = mealId(it);
            if (mid != null && byMust.containsKey(mid)) n += it.getAmount();
        }
        return n;
    }

    // ------------------------------------------------------------ drinking

    @EventHandler
    public void onDrink(PlayerItemConsumeEvent e) {
        ItemMeta m = e.getItem().getItemMeta();
        if (m == null) return;
        String id = m.getPersistentDataContainer().get(wineKey, PersistentDataType.STRING);
        if (id == null) return;
        WineType t = types.get(id);
        if (t == null) return;

        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        double level = Math.max(0.0, drunk.getOrDefault(u, 0.0) - (now - drunkAt.getOrDefault(u, now)) / 60000.0) + 1.0;
        drunk.put(u, level);
        drunkAt.put(u, now);

        int seconds = (int) Math.min(90, t.nausea() * (1.0 + 0.5 * (level - 1.0)));
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, seconds * 20, 0));
        if (level >= 3.0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 0));
        for (PotionEffect pe : t.effects()) p.addPotionEffect(pe);

        SurvivalModule s = plugin.survival();
        if (s != null && t.warmth() > 0) s.warm(p, t.warmth());
        p.sendActionBar(mm(level >= 3.0
                ? "<light_purple>The room is spinning..."
                : "<light_purple>The wine goes straight to your head..."));
        plugin.quest(p, "DRINK", "WINE");
    }
}
