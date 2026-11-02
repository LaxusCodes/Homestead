package dev.laxus.homestead.farming;

import dev.laxus.homestead.Season;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Builds crop profiles. The jar's bundled config.yml supplies defaults for any crop the server owner's config
 * does not define, so existing configs are never rewritten and new crops appear after an update.
 * A crop defined by the owner replaces the bundled one entirely; a broken crop is skipped with a warning.
 */
public final class CropRegistry {
    private final Map<Material, CropProfile> byBlock = new HashMap<>();
    private final Map<String, CropProfile> byId = new LinkedHashMap<>();
    private int defaultBiome = 50;

    public CropProfile forBlock(Material m) { return byBlock.get(m); }
    public CropProfile byId(String id) { return byId.get(id); }
    public Collection<CropProfile> all() { return byId.values(); }
    public int defaultBiome() { return defaultBiome; }

    public static CropRegistry load(FileConfiguration cfg, Reader bundled, Consumer<String> warn) {
        CropRegistry r = new CropRegistry();
        ConfigurationSection user = cfg.getConfigurationSection("farming");
        ConfigurationSection def = null;
        if (bundled != null) def = YamlConfiguration.loadConfiguration(bundled).getConfigurationSection("farming");
        int dflt = user != null ? user.getInt("default-biome-multiplier", 50) : 50;
        if (!GrowthMultiplier.isValid(dflt)) { warn.accept("farming.default-biome-multiplier must be 0-100, using 50"); dflt = 50; }
        r.defaultBiome = dflt;

        Set<String> ids = new LinkedHashSet<>();
        ConfigurationSection ug = user == null ? null : user.getConfigurationSection("growth");
        ConfigurationSection dg = def == null ? null : def.getConfigurationSection("growth");
        if (dg != null) ids.addAll(dg.getKeys(false));
        if (ug != null) ids.addAll(ug.getKeys(false));
        for (String id : ids) {
            ConfigurationSection s = ug != null && ug.isConfigurationSection(id) ? ug.getConfigurationSection(id) : dg == null ? null : dg.getConfigurationSection(id);
            if (s == null) continue;
            try {
                CropProfile p = parse(id, s, dflt, warn);
                if (p == null) continue;
                r.byId.put(id, p);
                for (Material m : p.blocks()) r.byBlock.put(m, p);
            } catch (RuntimeException ex) {
                warn.accept("farming.growth." + id + " skipped: " + ex.getMessage());
            }
        }
        return r;
    }

    private static CropProfile parse(String id, ConfigurationSection s, int dflt, Consumer<String> warn) {
        String at = "farming.growth." + id;
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (String n : s.getStringList("blocks")) {
            Material m = Material.matchMaterial(n);
            if (m == null || !m.isBlock()) warn.accept(at + ".blocks: unknown block '" + n + "' ignored");
            else blocks.add(m);
        }
        if (blocks.isEmpty()) { warn.accept(at + " has no valid blocks, skipped"); return null; }

        EnumMap<Season, Integer> seasons = new EnumMap<>(Season.class);
        for (Season se : Season.values()) seasons.put(se, 50);
        ConfigurationSection ss = s.getConfigurationSection("seasons");
        if (ss != null) for (String k : ss.getKeys(false)) {
            Season se = null;
            for (Season x : Season.values()) if (x.name().equalsIgnoreCase(k)) se = x;
            if (se == null) { warn.accept(at + ".seasons: unknown season '" + k + "' ignored"); continue; }
            seasons.put(se, pct(at + ".seasons." + k, ss.getInt(k, 50), warn));
        }

        int bd = s.contains("biome-default") ? pct(at + ".biome-default", s.getInt("biome-default"), warn) : dflt;
        Map<Biome, Integer> biomes = new HashMap<>();
        ConfigurationSection bs = s.getConfigurationSection("biomes");
        if (bs != null) {
            // later groups win if a biome is accidentally listed twice: unsuitable is the safest
            put(biomes, bs.getStringList("unfavorable"), 50, at, warn);
            put(biomes, bs.getStringList("suitable"), 100, at, warn);
            put(biomes, bs.getStringList("unsuitable"), 0, at, warn);
        }
        return new CropProfile(id, Set.copyOf(blocks), seasons, biomes, bd);
    }

    private static int pct(String path, int v, Consumer<String> warn) {
        if (!GrowthMultiplier.isValid(v)) { warn.accept(path + " = " + v + " is outside 0-100, clamped"); return GrowthMultiplier.normalize(v); }
        return v;
    }

    private static void put(Map<Biome, Integer> out, List<String> names, int pct, String at, Consumer<String> warn) {
        for (String n : names) {
            // Look biomes up by namespaced key, never by display name. Unknown/future ids only warn.
            NamespacedKey key = NamespacedKey.fromString(n.toLowerCase(Locale.ROOT));
            Biome b = key == null ? null : Registry.BIOME.get(key);
            if (b == null) warn.accept(at + ".biomes: unknown biome '" + n + "' ignored");
            else out.put(b, pct);
        }
    }
}
