package dev.laxus.homestead.farming;

import java.util.random.RandomGenerator;

/**
 * Integer percentage maths for the farming system (no Bukkit types, so it is unit-testable).
 * 100 = vanilla speed, 50 = half, 25 = quarter, 0 = no growth. Percentages are never added or averaged.
 */
public final class GrowthMultiplier {
    private GrowthMultiplier() {}

    public static boolean isValid(int pct) {
        return pct >= 0 && pct <= 100;
    }

    /** Invalid values are clamped into 0..100 (callers log a warning when {@link #isValid} is false). */
    public static int normalize(int pct) {
        return Math.max(0, Math.min(100, pct));
    }

    /** FINAL = SEASON * BIOME / 100, exact integer arithmetic (50 x 50 = 25). */
    public static int combine(int seasonPct, int biomePct) {
        return normalize(seasonPct) * normalize(biomePct) / 100;
    }

    /**
     * Per-attempt probability gate: 100 always allows, 0 never allows, otherwise allows about pct% of attempts.
     * Vanilla has already decided that this attempt would grow, so scaling it by pct gives pct% of vanilla speed.
     */
    public static boolean allow(int pct, RandomGenerator rng) {
        if (pct >= 100) return true;
        if (pct <= 0) return false;
        return rng.nextInt(100) < pct;
    }
}
