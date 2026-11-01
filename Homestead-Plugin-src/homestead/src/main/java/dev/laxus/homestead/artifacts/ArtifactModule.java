package dev.laxus.homestead.artifacts;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.Season;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * The Golden Leaf: a rare relic that sometimes drops from leaves (more often in autumn).
 * Hold it in either hand to receive its blessings (effects from config).
 */
public final class ArtifactModule extends AbstractModule {
    private final NamespacedKey leafKey;
    private final List<PotionEffect> effects = new ArrayList<>();

    public ArtifactModule(HomesteadPlugin plugin) {
        super(plugin);
        leafKey = new NamespacedKey(plugin, "golden_leaf");
    }

    @Override
    protected void onEnable() {
        for (String s : plugin.getConfig().getStringList("artifacts.golden-leaf.effects")) {
            try {
                String[] parts = s.split(":");
                PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase()));
                int secs = parts.length > 1 ? Integer.parseInt(parts[1]) : 6;
                int amp = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                if (type != null) effects.add(new PotionEffect(type, secs * 20, amp, true, false, true));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Golden Leaf: bad effect '" + s + "'");
            }
        }
        every(40L, this::blessings);
    }

    @Override
    protected void onDisable() {
        effects.clear();
    }

    public ItemStack createGoldenLeaf() {
        String base = plugin.getConfig().getString("artifacts.golden-leaf.base", "GOLD_NUGGET");
        Material mat = Material.matchMaterial(base);
        ItemStack it = new ItemStack(mat == null ? Material.GOLD_NUGGET : mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(item(plugin.getConfig().getString("artifacts.golden-leaf.name", "<gold>Golden Leaf")));
        m.lore(List.of(item("<gray>A relic of autumn."), item("<yellow>Hold it to feel its blessing.")));
        int cmd = plugin.getConfig().getInt("artifacts.golden-leaf.cmd", 0);
        if (cmd > 0) m.setCustomModelData(cmd);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(leafKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isLeaf(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(leafKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (!Tag.LEAVES.isTagged(b.getType()) || p.getGameMode() == GameMode.CREATIVE) return;
        if (!plugin.worldAllowed(b.getWorld())) return;

        double chance = plugin.getConfig().getDouble("artifacts.golden-leaf.chance", 0.004);
        int days = plugin.getConfig().getInt("seasons.days-per-season", 7);
        if (Season.of(b.getWorld(), days) == Season.AUTUMN) {
            chance *= plugin.getConfig().getDouble("artifacts.golden-leaf.autumn-multiplier", 3.0);
        }
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;

        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().dropItemNaturally(loc, createGoldenLeaf());
        b.getWorld().spawnParticle(Particle.END_ROD, loc, 14, 0.3, 0.3, 0.3, 0.02);
        b.getWorld().playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        p.sendMessage(mm("<gold>✦ <yellow>A golden leaf flutters down..."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isLeaf(e.getItem().getItemStack())) {
            plugin.quest(p, "FIND", "golden_leaf");
        }
    }

    private void blessings() {
        if (effects.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isLeaf(p.getInventory().getItemInMainHand()) || isLeaf(p.getInventory().getItemInOffHand())) {
                for (PotionEffect pe : effects) p.addPotionEffect(pe);
            }
        }
    }
}
