package dev.laxus.homestead.farming;

import dev.laxus.homestead.Season;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** One crop's season and biome percentages, loaded from config.yml (farming.growth.<id>). */
public record CropProfile(String id, Set<Material> blocks, EnumMap<Season, Integer> seasons,
                          Map<Biome, Integer> biomes, int biomeDefault) {

    public int seasonPct(Season s) {
        return seasons.getOrDefault(s, 50);
    }

    public int biomePct(Biome b) {
        return biomes.getOrDefault(b, biomeDefault);
    }

    public int finalPct(Season s, Biome b) {
        return GrowthMultiplier.combine(seasonPct(s), biomePct(b));
    }

    public String displayName() {
        String[] w = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String x : w) sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));
        return sb.toString();
    }
}
