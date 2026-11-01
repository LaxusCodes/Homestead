package dev.laxus.homestead.survival;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.Season;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.RayTraceResult;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static dev.laxus.homestead.Text.mm;

/** Seasons, temperature and thirst. */
public final class SurvivalModule extends AbstractModule {
    private static final double MAX_THIRST = 20.0;
    private static final Set<Material> HEAT = EnumSet.of(
            Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FURNACE,
            Material.BLAST_FURNACE, Material.SMOKER);

    private final NamespacedKey thirstKey;
    private final Map<UUID, Double> thirst = new HashMap<>();
    private final Map<UUID, Long> warmUntil = new HashMap<>();
    private final Map<UUID, Long> drinkCooldown = new HashMap<>();
    private final Map<UUID, Season> worldSeason = new HashMap<>();
    private int tick;

    public SurvivalModule(HomesteadPlugin plugin) {
        super(plugin);
        this.thirstKey = new NamespacedKey(plugin, "thirst");
    }

    @Override
    protected void onEnable() {
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        every(20L, this::tickSecond);
    }

    @Override
    protected void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) save(p);
        thirst.clear();
        warmUntil.clear();
        drinkCooldown.clear();
        worldSeason.clear();
    }

    // ------------------------------------------------------------------ API

    public double thirst(Player p) {
        return thirst.computeIfAbsent(p.getUniqueId(), k -> MAX_THIRST);
    }

    public void addThirst(Player p, double amount) {
        double v = Math.max(0.0, Math.min(MAX_THIRST, thirst(p) + amount));
        thirst.put(p.getUniqueId(), v);
    }

    /** Makes the player immune to cold for the given number of seconds (hot meals). */
    public void warm(Player p, int seconds) {
        warmUntil.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    public Season season(World w) {
        return Season.of(w, plugin.getConfig().getInt("seasons.days-per-season", 7));
    }

    public double effectiveTemp(Player p) {
        FileConfiguration c = plugin.getConfig();
        Location l = p.getLocation();
        World w = l.getWorld();
        double t = l.getBlock().getTemperature();
        t += season(w).tempMod;
        long time = w.getTime();
        if (time >= 13000 && time < 23000) t -= c.getDouble("temperature.night-drop", 0.25);
        if (l.getY() > 90) t -= (l.getY() - 90) * 0.01;
        if (w.hasStorm()) t -= 0.1;
        return t;
    }

    // ------------------------------------------------------------ main loop

    private void tickSecond() {
        tick++;
        FileConfiguration c = plugin.getConfig();
        int days = c.getInt("seasons.days-per-season", 7);

        for (World w : Bukkit.getWorlds()) {
            if (!plugin.worldAllowed(w) || w.getEnvironment() != World.Environment.NORMAL) continue;
            Season now = Season.of(w, days);
            Season prev = worldSeason.put(w.getUID(), now);
            if (prev != null && prev != now) {
                for (Player p : w.getPlayers()) {
                    p.showTitle(Title.title(mm(now.display), mm("<gray>The season has changed")));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
                }
            }
        }

        boolean thirstOn = c.getBoolean("thirst.enabled", true);
        boolean coldOn = c.getBoolean("temperature.enabled", true);
        boolean hud = c.getBoolean("hud.enabled", true);
        double coldBelow = c.getDouble("temperature.cold-below", 0.1);
        double hotAbove = c.getDouble("temperature.hot-above", 1.2);
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode gm = p.getGameMode();
            World w = p.getWorld();
            if (p.isDead() || (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE)) continue;
            if (!plugin.worldAllowed(w) || w.getEnvironment() != World.Environment.NORMAL) continue;

            double temp = effectiveTemp(p);

            if (thirstOn) {
                double perSec = drainPerMinute() / 60.0;
                if (p.isSprinting()) perSec *= Math.max(1.0, c.getDouble("thirst.sprint-multiplier", 1.2));
                if (temp > hotAbove) perSec *= Math.max(1.0, c.getDouble("thirst.heat-multiplier", 1.25));
                double t = Math.max(0.0, thirst(p) - perSec);
                thirst.put(p.getUniqueId(), t);
                if (t <= 0.0 && tick % 4 == 0 && p.getHealth() > 2.0) {
                    p.damage(1.0);
                }
                if (t < 6.0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, true, false, false));
                }
            }

            if (coldOn && temp < coldBelow
                    && warmUntil.getOrDefault(p.getUniqueId(), 0L) < now
                    && !wearsLeather(p) && !nearHeat(p.getLocation())) {
                p.setFreezeTicks(c.getInt("temperature.freeze-ticks", 100));
            }

            if (hud) {
                p.sendActionBar(mm(hudText(p, w, temp, coldBelow, hotAbove)));
            }
        }
    }

    /** Full bar lasts 100/drainPerMinute minutes: 1.0/min = 20 minutes. Legacy configs shipped 1.2. */
    private double drainPerMinute() {
        double v = plugin.getConfig().getDouble("thirst.drain-per-minute", 1.0);
        if (v == 1.2) {   // pre-rebalance shipped default: drained a full bar 20% too fast
            plugin.getLogger().info("thirst.drain-per-minute: 1.2 is legacy; using 1.0 (a full bar lasts 20 minutes).");
            v = 1.0;
        }
        return v;
    }

    private String hudText(Player p, World w, double temp, double coldBelow, double hotAbove) {
        FileConfiguration c = plugin.getConfig();
        Season s = season(w);
        int level;
        String label;
        if (temp < 0.0) { level = 0; label = "<blue>Freezing"; }
        else if (temp < coldBelow) { level = 1; label = "<aqua>Cold"; }
        else if (temp < 1.0) { level = 2; label = "<green>Mild"; }
        else if (temp < hotAbove) { level = 3; label = "<gold>Warm"; }
        else { level = 4; label = "<red>Hot"; }

        // text fallback (no resource pack): hud.style: text
        if (!"icons".equalsIgnoreCase(c.getString("hud.style", "icons"))) {
            int full = (int) Math.ceil(thirst(p) / 2.0);
            return "<aqua>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(10 - full)
                    + " <gray>| " + s.display + " <gray>| " + label;
        }

        /*
         * Icon style - glyph overlay on the action bar line, anchored to the bottom HUD:
         *   thirst bar: 81px wide (same as the hunger bar), pixel-locked directly above it
         *   temperature bar + season icon: same row, on the right.
         * The action bar centres the string, so a leading run of invisible spacer glyphs (space
         * provider: E020 = 9px, E021 = 3px, E022 = 1px) puts the left edge exactly at
         * (screen centre + 10px) = the left edge of the hunger bar.
         *
         * Glyph map: E000-E014 = thirst bar (level 0..20), E015-E019 = temp bar
         * (0=freezing..4=hot), E01A-E01D = seasons.
         * Advances: thirst glyph 82px, temp glyph 46px, season glyph 10px (width + 1).
         */
        int thirstLevel = Math.max(0, Math.min(20,
                (int) Math.round(Math.max(0.0, Math.min(MAX_THIRST, thirst(p))))));
        int tempRight = Math.max(140, c.getInt("hud.temp-right", 140));
        int gap = Math.max(3, tempRight - 137);              // px between thirst bar and temperature bar
        if ((138 + gap) % 2 != 0) gap++;                     // keep the centred string on whole pixels
        int rest = 82 + gap + 46 + 10;                       // everything after the leading spacer
        int lead = 20 + rest;                                // puts the thirst bar's left edge at centre + 10

        StringBuilder sb = new StringBuilder("<white><font:homestead:hud>");
        sb.append(spacers(lead));
        sb.append((char) (0xE000 + thirstLevel));            // thirst bar
        sb.append(spacers(gap));                             // gap between the bars
        sb.append((char) (0xE015 + level));                  // temperature bar
        sb.append((char) (0xE01A + s.ordinal()));            // season icon
        sb.append("</font>");
        if (c.getBoolean("hud.labels", false)) {
            sb.append(" ").append(s.display).append(" ").append(label);
        }
        return sb.toString();
    }

    /** Spacer glyph chain for an exact pixel distance (9px, 3px and 1px glyphs from the space provider). */
    private String spacers(int px) {
        if (px <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int n9 = px / 9;
        int rem = px % 9;
        int n3 = rem / 3;
        int n1 = rem % 3;
        for (int i = 0; i < n9; i++) sb.append((char) 0xE020);
        for (int i = 0; i < n3; i++) sb.append((char) 0xE021);
        for (int i = 0; i < n1; i++) sb.append((char) 0xE022);
        return sb.toString();
    }

    private boolean wearsLeather(Player p) {
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it != null && it.getType().name().startsWith("LEATHER_")) return true;
        }
        return false;
    }

    private boolean nearHeat(Location l) {
        Block c = l.getBlock();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block b = c.getRelative(dx, dy, dz);
                    Material m = b.getType();
                    if (!HEAT.contains(m)) continue;
                    if (m == Material.FIRE || m == Material.SOUL_FIRE
                            || m == Material.LAVA || m == Material.MAGMA_BLOCK) return true;
                    if (b.getBlockData() instanceof Lightable li && li.isLit()) return true;
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------------- events

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!plugin.getConfig().getBoolean("thirst.enabled", true)) return;
        ItemStack it = e.getItem();
        // Homestead drinks (boiled water, musts, wines, meals) are handled by the cooking module and
        // are always clean. The vanilla drinks below count as unboiled water.
        if (plugin.isHomesteadItem(it)) return;
        double add = 0.0;
        if (it.getType() == Material.POTION && it.getItemMeta() instanceof PotionMeta pm
                && pm.getBasePotionType() == PotionType.WATER) {
            add = plugin.getConfig().getDouble("thirst.water-bottle", 6.0);
            maybePoison(e.getPlayer());
            plugin.quest(e.getPlayer(), "DRINK", "WATER");
        } else if (it.getType() == Material.MILK_BUCKET) {
            add = 4.0;
        } else if (it.getType() == Material.MELON_SLICE) {
            add = 1.0;
        }
        if (add > 0) addThirst(e.getPlayer(), add);
    }

    /** Unboiled water can make you sick - short Poison. Boil water in the pot to make it safe. */
    private void maybePoison(Player p) {
        double dirty = plugin.getConfig().getDouble("thirst.dirty-water-chance", 0.35);
        if (ThreadLocalRandom.current().nextDouble() < dirty) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 0));
            p.sendActionBar(mm("<yellow>That water wasn't boiled... boil it in the pot first."));
        }
    }

    /** Sneak + right-click (empty hand) while looking at water to drink from it. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || p.getInventory().getItemInMainHand().getType() != Material.AIR) return;
        if (!plugin.getConfig().getBoolean("thirst.enabled", true)) return;
        Block clicked = e.getClickedBlock();
        if (clicked != null && clicked.getType().isInteractable()) return;

        RayTraceResult r = p.rayTraceBlocks(4.0, FluidCollisionMode.SOURCE_ONLY);
        if (r == null || r.getHitBlock() == null || r.getHitBlock().getType() != Material.WATER) return;

        long now = System.currentTimeMillis();
        if (drinkCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        drinkCooldown.put(p.getUniqueId(), now + 600L);

        addThirst(p, plugin.getConfig().getDouble("thirst.water-source", 2.0));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.6f, 1f);
        plugin.quest(p, "DRINK", "WATER");
        maybePoison(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        save(e.getPlayer());
        UUID id = e.getPlayer().getUniqueId();
        thirst.remove(id);
        warmUntil.remove(id);
        drinkCooldown.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        thirst.put(e.getPlayer().getUniqueId(), MAX_THIRST);
    }

    private void load(Player p) {
        Double v = p.getPersistentDataContainer().get(thirstKey, PersistentDataType.DOUBLE);
        thirst.put(p.getUniqueId(), v == null ? MAX_THIRST : v);
    }

    private void save(Player p) {
        Double v = thirst.get(p.getUniqueId());
        if (v != null) p.getPersistentDataContainer().set(thirstKey, PersistentDataType.DOUBLE, v);
    }
}
