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
 *     The timer starts the moment the bottles land inside and counts down live on the action bar.
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
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk c : w.getLoadedChunks()) trackChunk(c);
        }
        every(20L, this::tick);
    }

    @Override
    protected void onDisable() {
        types.clear();
        byMust.clear();
        tracked.clear();
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

    /** Id of the must (cooking recipe) a wine type ferments from, or null. */
    public String mustOf(String wineId) {
        WineType t = types.get(wineId);
        return t == null ? null : t.from();
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
     * Barrels are plain vanilla barrels - no special right-click. Open one and put must bottles
     * inside. The timer starts the instant the bottles land in the barrel (click/drag/shift-click
     * are all detected), is shown live on the action bar while the barrel is open, and keeps running
     * while it is closed: fermenting barrels are tracked and converted in place once ferment-ticks
     * have passed, as long as their chunk is loaded.
     */

    private final java.util.Set<String> tracked = new java.util.HashSet<>();
    private int tickCounter = 0;

    private static String keyOf(org.bukkit.block.Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private org.bukkit.block.Block blockOf(String key) {
        String[] p = key.split(";");
        org.bukkit.World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]), z = Integer.parseInt(p[3]);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z);
    }

    private void trackChunk(org.bukkit.Chunk c) {
        for (org.bukkit.block.BlockState st : c.getTileEntities()) {
            if (st instanceof Barrel b && b.getPersistentDataContainer().has(startKey, PersistentDataType.LONG)) {
                tracked.add(keyOf(b.getBlock()));
            }
        }
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
        trackChunk(e.getChunk());
    }

    /** Opening a barrel brings its state up to date, then shows the live timer. */
    @EventHandler
    public void onBarrelOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (barrel.getBlock().getType() != Material.BARREL || !plugin.worldAllowed(barrel.getWorld())) return;
        scan(barrel);
        showTimer(p, barrel);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (barrel.getBlock().getType() != Material.BARREL) return;
        scan(barrel);
    }

    /** Bottles moved in or out of an open barrel: re-scan next tick so the timer starts right away. */
    @EventHandler
    public void onBarrelClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        scanSoon(e.getView().getTopInventory());
    }

    @EventHandler
    public void onBarrelDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        scanSoon(e.getView().getTopInventory());
    }

    private void scanSoon(org.bukkit.inventory.Inventory top) {
        if (top.getType() != org.bukkit.event.inventory.InventoryType.BARREL) return;
        if (!(top.getHolder() instanceof Barrel holder)) return;
        org.bukkit.block.Block block = holder.getBlock();
        if (!plugin.worldAllowed(block.getWorld())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.BARREL && block.getState() instanceof Barrel fresh) scan(fresh);
        });
    }

    /** Once a second: live timer for everyone looking into a barrel; every 5s: closed tracked barrels. */
    private void tick() {
        tickCounter++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getType() != org.bukkit.event.inventory.InventoryType.BARREL) continue;
            if (top.getHolder() instanceof Barrel barrel && barrel.getBlock().getType() == Material.BARREL
                    && plugin.worldAllowed(barrel.getWorld())) {
                scan(barrel);
                showTimer(p, barrel);
            }
        }
        if (tickCounter % 5 != 0) return;
        for (String key : new ArrayList<>(tracked)) {
            org.bukkit.block.Block b = blockOf(key);
            if (b == null) continue;                       // chunk not loaded: keep tracking, skip for now
            if (b.getType() != Material.BARREL || !(b.getState() instanceof Barrel barrel)) {
                tracked.remove(key);
                continue;
            }
            scan(barrel);
            if (barrel.getPersistentDataContainer().has(startKey, PersistentDataType.LONG)) {
                b.getWorld().spawnParticle(org.bukkit.Particle.COMPOSTER, b.getLocation().add(0.5, 1.05, 0.5), 2, 0.2, 0.05, 0.2, 0);
            } else {
                tracked.remove(key);
            }
        }
    }

    /** Action bar line: progress bar, percent and time left (or "ready"). */
    private void showTimer(Player p, Barrel barrel) {
        PersistentDataContainer pdc = barrel.getPersistentDataContainer();
        long start = pdc.getOrDefault(startKey, PersistentDataType.LONG, -1L);
        WineType t = types.get(pdc.get(typeKey, PersistentDataType.STRING));
        int count = countMust(barrel);
        if (start < 0 || t == null || count == 0) return;
        long ferment = Math.max(1L, plugin.getConfig().getLong("wine.ferment-ticks", 12000L));
        long elapsed = Math.max(0L, barrel.getWorld().getGameTime() - start);
        double frac = Math.min(1.0, (double) elapsed / ferment);
        int filled = (int) Math.round(frac * 12);
        String bar = "<gold>" + "\u25AE".repeat(filled) + "<dark_gray>" + "\u25AE".repeat(12 - filled);
        long leftSec = Math.max(0L, (ferment - elapsed) / 20L);
        String time = String.format("%d:%02d", leftSec / 60, leftSec % 60);
        p.sendActionBar(mm("<light_purple>" + count + "x " + t.name() + " <gray>" + bar
                + " <white>" + (int) Math.round(frac * 100) + "% <gray>- <yellow>" + time + " left"));
    }

    /** One scan: refresh the ferment state of a barrel, converting must bottles when ready. */
    private void scan(Barrel barrel) {
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

        String key = keyOf(barrel.getBlock());
        if (count == 0) {
            if (stored != null || start >= 0) {
                pdc.remove(typeKey);
                pdc.remove(startKey);
                barrel.update();
            }
            tracked.remove(key);
            return;
        }

        if (stored == null || start < 0 || !stored.equals(found.id())) {
            // a new batch of must was put in - the timer starts now
            pdc.set(typeKey, PersistentDataType.STRING, found.id());
            pdc.set(startKey, PersistentDataType.LONG, barrel.getWorld().getGameTime());
            barrel.update();
            tracked.add(key);
            return;
        }
        tracked.add(key);

        long ferment = plugin.getConfig().getLong("wine.ferment-ticks", 12000L);
        long elapsed = barrel.getWorld().getGameTime() - start;
        if (elapsed < ferment) return;

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
        if (converted == 0) return;
        inv.setContents(contents);
        pdc.remove(typeKey);
        pdc.remove(startKey);
        barrel.update();
        tracked.remove(key);
        barrel.getWorld().playSound(barrel.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.2f);
        Player nearest = null;
        for (Player p : barrel.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(barrel.getLocation()) < 256) {
                p.sendActionBar(mm("<green>The wine has finished fermenting."));
                if (nearest == null) nearest = p;
            }
        }
        if (nearest != null && plugin.quests() != null) {
            plugin.quests().progress(nearest, "BREW", found.id(), converted);
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
