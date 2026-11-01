package dev.laxus.homestead;

import org.bukkit.World;

public enum Season {
    SPRING("<green>Spring", 0.05),
    SUMMER("<yellow>Summer", 0.30),
    AUTUMN("<gold>Autumn", -0.05),
    WINTER("<aqua>Winter", -0.45);

    public final String display;
    public final double tempMod;

    Season(String display, double tempMod) {
        this.display = display;
        this.tempMod = tempMod;
    }

    /** Season derived from the world's total elapsed time. Day 0 is the start of Spring. */
    public static Season of(World w, int daysPerSeason) {
        long day = w.getFullTime() / 24000L;
        int idx = (int) ((day / Math.max(1, daysPerSeason)) % 4L);
        return values()[idx];
    }

    public static int dayInSeason(World w, int daysPerSeason) {
        long day = w.getFullTime() / 24000L;
        return (int) (day % Math.max(1, daysPerSeason)) + 1;
    }

    public String key() {
        return name().toLowerCase();
    }
}
