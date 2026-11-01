package dev.laxus.homestead.farming;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.Season;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockGrowEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Crops grow faster or slower depending on the season.
 * growth below 1.0 = chance a growth tick is allowed; above 1.0 = chance of a bonus growth stage.
 * Crops with strong light nearby (torches, glowstone...) count as a heated greenhouse and ignore winter.
 */
public final class FarmingModule extends AbstractModule {

    public FarmingModule(HomesteadPlugin plugin) {
        super(plugin);
    }

    @Override
    protected void onEnable() {
        // event-driven only
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        if (!plugin.worldAllowed(b.getWorld())) return;
        BlockState next = e.getNewState();
        if (!(next.getBlockData() instanceof Ageable)) return;

        int days = plugin.getConfig().getInt("seasons.days-per-season", 7);
        Season s = Season.of(b.getWorld(), days);
        double mult = plugin.getConfig().getDouble("farming.seasons." + s.key(), 1.0);

        int greenhouseLight = plugin.getConfig().getInt("farming.greenhouse-light", 12);
        if (mult < 1.0 && b.getLightFromBlocks() >= greenhouseLight) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (mult < 1.0) {
            if (rnd.nextDouble() > mult) e.setCancelled(true);
        } else if (mult > 1.0 && rnd.nextDouble() < (mult - 1.0)) {
            Ageable age = (Ageable) next.getBlockData();
            if (age.getAge() < age.getMaximumAge()) {
                age.setAge(age.getAge() + 1);
                next.setBlockData(age);
            }
        }
    }
}
