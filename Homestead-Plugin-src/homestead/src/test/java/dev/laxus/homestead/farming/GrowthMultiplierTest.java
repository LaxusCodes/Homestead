package dev.laxus.homestead.farming;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GrowthMultiplierTest {
    @Test void combine() {
        assertEquals(100, GrowthMultiplier.combine(100, 100));
        assertEquals(50, GrowthMultiplier.combine(100, 50));
        assertEquals(50, GrowthMultiplier.combine(50, 100));
        assertEquals(25, GrowthMultiplier.combine(50, 50));
        assertEquals(0, GrowthMultiplier.combine(0, 100));
        assertEquals(0, GrowthMultiplier.combine(100, 0));
    }

    @Test void invalidIsNormalized() {
        assertFalse(GrowthMultiplier.isValid(-1));
        assertFalse(GrowthMultiplier.isValid(101));
        assertEquals(100, GrowthMultiplier.combine(250, 100));
        assertEquals(0, GrowthMultiplier.combine(-5, 100));
    }

    @Test void rates() {
        Random r = new Random(42);
        for (int i = 0; i < 1000; i++) {
            assertTrue(GrowthMultiplier.allow(100, r));
            assertFalse(GrowthMultiplier.allow(0, r));
        }
        for (int pct : new int[]{50, 25}) {
            int hit = 0, n = 200_000;
            for (int i = 0; i < n; i++) if (GrowthMultiplier.allow(pct, r)) hit++;
            assertEquals(pct / 100.0, hit / (double) n, 0.01);
        }
    }
}
