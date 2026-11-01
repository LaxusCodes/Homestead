package dev.laxus.homestead;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Base class: registers itself as a listener and tracks its repeating tasks. */
public abstract class AbstractModule implements Listener {
    protected final HomesteadPlugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    protected AbstractModule(HomesteadPlugin plugin) {
        this.plugin = plugin;
    }

    public final void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        onEnable();
    }

    public final void disable() {
        beforeDisable();
        HandlerList.unregisterAll(this);
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        onDisable();
    }

    protected abstract void onEnable();

    /** Runs while this module's listeners are still registered (e.g. to close inventories so they save). */
    protected void beforeDisable() {}

    protected void onDisable() {}

    protected BukkitTask every(long ticks, Runnable r) {
        BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, r, ticks, ticks);
        tasks.add(t);
        return t;
    }
}
