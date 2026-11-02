package dev.laxus.homestead.farming;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.Season;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static dev.laxus.homestead.Text.mm;

/**
 * Seasonal + biome crop growth: final% = season% x biome% / 100, rolled once per vanilla growth attempt
 * (100 = vanilla, 50 = half, 25 = quarter, 0 = never). No light / greenhouse bonus.
 * The season always comes from Season.of(world, seasons.days-per-season), the same clock as /homestead season.
 * Config reload = plugin.reload() (modules are stopped and rebuilt), so no separate reload path is needed.
 */
public final class FarmingModule extends AbstractModule {
    private volatile CropRegistry crops = new CropRegistry();
    private final Map<UUID, Long> lastInspect = new ConcurrentHashMap<>();   // bounded by online players
    private boolean boneMeal = true, inspect = true;

    public FarmingModule(HomesteadPlugin plugin) {
        super(plugin);
    }

    @Override
    protected void onEnable() {
        var cfg = plugin.getConfig();
        boneMeal = cfg.getBoolean("farming.bone-meal-respects-multiplier", true);
        inspect = cfg.getBoolean("farming.inspect", true);
        // the bundled config.yml supplies defaults for crops missing from the owner's file; the owner's file is never rewritten
        try (var in = plugin.getResource("config.yml");
             var rd = in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8)) {
            crops = CropRegistry.load(cfg, rd, plugin.getLogger()::warning);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled farming defaults: " + e.getMessage());
            crops = CropRegistry.load(cfg, null, plugin.getLogger()::warning);
        }
        if (cfg.contains("farming.greenhouse-light") || cfg.contains("farming.seasons")) {
            plugin.getLogger().info("Farming: farming.greenhouse-light / farming.seasons are no longer used "
                    + "(growth is now season % x biome % under farming.growth). You can delete them.");
        }
        plugin.getLogger().info("Farming: " + crops.all().size() + " crop profiles loaded.");
    }

    @Override
    protected void onDisable() {
        lastInspect.clear();
        crops = new CropRegistry();
    }

    public CropRegistry registry() { return crops; }

    private int days() { return plugin.getConfig().getInt("seasons.days-per-season", 7); }

    /** {season %, biome %, final %} for the plant growing at this block. */
    public int[] percentages(CropProfile p, Block b) {
        Season se = Season.of(b.getWorld(), days());
        Biome bi = b.getBiome();
        return new int[]{p.seasonPct(se), p.biomePct(bi), p.finalPct(se, bi)};
    }

    private boolean allowGrowth(CropProfile p, Block b) {
        return GrowthMultiplier.allow(percentages(p, b)[2], ThreadLocalRandom.current());
    }

    // natural growth (crops, stems, fruit, cane, cactus, bamboo, kelp, vines, berries, nether wart, chorus ...).
    // For plants that grow by creating a block (cane, cactus, fruit) getBlock() is the empty target,
    // so the plant is identified by the NEW state.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        CropProfile p = crops.forBlock(e.getNewState().getType());
        if (p == null || !plugin.worldAllowed(e.getBlock().getWorld())) return;
        if (!allowGrowth(p, e.getBlock())) e.setCancelled(true);
    }

    // mushroom spread (grass / mycelium spread are not in the crop list, so they are untouched)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        CropProfile p = crops.forBlock(e.getNewState().getType());
        if (p == null || !plugin.worldAllowed(e.getBlock().getWorld())) return;
        if (!allowGrowth(p, e.getBlock())) e.setCancelled(true);
    }

    // huge mushrooms and huge fungi grown from the small form (bone meal or random tick)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructure(StructureGrowEvent e) {
        String id = switch (e.getSpecies()) {
            case BROWN_MUSHROOM -> "brown_mushroom";
            case RED_MUSHROOM -> "red_mushroom";
            case CRIMSON_FUNGUS -> "crimson_fungus";
            case WARPED_FUNGUS -> "warped_fungus";
            default -> null;
        };
        CropProfile p = id == null ? null : crops.byId(id);
        Block b = e.getLocation().getBlock();
        if (p == null || !plugin.worldAllowed(b.getWorld())) return;
        if (e.isFromBonemeal() && !boneMeal) return;
        if (!allowGrowth(p, b)) e.setCancelled(true);
    }

    // bone meal on crops: one roll per use. Mushrooms / fungi are rolled in onStructure instead, never twice.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneMeal(BlockFertilizeEvent e) {
        if (!boneMeal) return;
        Block b = e.getBlock();
        CropProfile p = crops.forBlock(b.getType());
        if (p == null || !plugin.worldAllowed(b.getWorld())) return;
        String id = p.id();
        if (id.equals("brown_mushroom") || id.equals("red_mushroom") || id.equals("crimson_fungus") || id.equals("warped_fungus")) return;
        if (!allowGrowth(p, b)) e.setCancelled(true);
    }

    // inspection: sneak + right-click a crop with an empty hand (also /homestead crop)
    @EventHandler
    public void onInspect(PlayerInteractEvent e) {
        if (!inspect || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player pl = e.getPlayer();
        if (!pl.isSneaking() || !pl.getInventory().getItemInMainHand().getType().isAir()) return;
        Block b = e.getClickedBlock();
        if (b == null || crops.forBlock(b.getType()) == null) return;
        long now = System.currentTimeMillis();
        Long last = lastInspect.get(pl.getUniqueId());
        if (last != null && now - last < 1000) return;
        lastInspect.put(pl.getUniqueId(), now);
        inspect(pl, b);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastInspect.remove(e.getPlayer().getUniqueId());
    }

    public void inspect(Player pl, Block b) {
        CropProfile p = b == null ? null : crops.forBlock(b.getType());
        if (p == null) {
            pl.sendMessage(mm("<gray>Look at a crop or plant to see how it grows here."));
            return;
        }
        Season se = Season.of(b.getWorld(), days());
        int[] v = percentages(p, b);
        String why = v[2] == 0 ? (v[0] == 0 && v[1] == 0 ? " <red>(wrong season and wrong biome)"
                : v[0] == 0 ? " <red>(wrong season)" : " <red>(wrong biome)") : "";
        pl.sendMessage(mm("<gold>" + p.displayName() + "<gray> | " + se.display + "<gray> | <white>" + pretty(b.getBiome())
                + "<gray>\nSeason <white>" + v[0] + "%<gray>  Biome <white>" + v[1] + "%<gray>  Growth <yellow>" + v[2] + "%" + why));
    }

    private static String pretty(Biome b) {
        String k = b.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }
}
