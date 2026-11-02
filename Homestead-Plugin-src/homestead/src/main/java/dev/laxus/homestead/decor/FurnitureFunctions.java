package dev.laxus.homestead.decor;

import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.cooking.CookingModule;
import dev.laxus.homestead.survival.SurvivalModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * What furniture actually DOES (no more ghost blocks). Driven by two keys in config.yml per furniture item:
 *   storage-rows: N   - cabinets, baskets, bale: a real chest-like inventory saved on the furniture itself
 *   station: stove    - burns fuel, grills raw food, warms players nearby, heats a cooking pot beside it
 *   station: pot      - a cooking pot that works exactly like cauldron-on-campfire when it has heat
 *   station: board    - cutting board: knife in one hand, ingredient in the other, right-click
 * DecorModule calls handle() for a plain (non-sneaking) right-click on a piece of furniture.
 */
public final class FurnitureFunctions {

    private static final class StorageHolder implements InventoryHolder {
        final UUID box;
        Inventory inv;

        StorageHolder(UUID box) {
            this.box = box;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final HomesteadPlugin plugin;
    private final DecorModule decor;
    private final NamespacedKey itemsKey;
    private final NamespacedKey stoveKey;
    private final NamespacedKey knifeKey;
    private final Map<UUID, Inventory> open = new HashMap<>();
    private final Set<UUID> litStoves = new HashSet<>();
    private final Random rng = new Random();

    FurnitureFunctions(HomesteadPlugin plugin, DecorModule decor) {
        this.plugin = plugin;
        this.decor = decor;
        itemsKey = new NamespacedKey(plugin, "furniture_items");
        stoveKey = new NamespacedKey(plugin, "stove_until");
        knifeKey = new NamespacedKey(plugin, "kitchen_knife");
    }

    // ------------------------------------------------------------------ dispatch

    /** @return true when the click was used by a function (so it must not also seat the player). */
    boolean handle(Player p, Entity furniture, DecorModule.Def d) {
        if (d.storageRows() > 0) {
            openStorage(p, furniture, d);
            return true;
        }
        String station = d.station() == null ? "" : d.station();
        switch (station) {
            case "stove" -> useStove(p, furniture);
            case "pot" -> usePot(p, furniture);
            case "board" -> useBoard(p, furniture);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean mayUse(Player p, Entity furniture) {
        if (!plugin.getConfig().getBoolean("decor.private-storage", false)) return true;
        String owner = decor.ownerOf(furniture);
        if (owner == null || owner.equals(p.getUniqueId().toString()) || p.hasPermission("homestead.admin")) return true;
        p.sendActionBar(mm("<red>That isn't yours."));
        return false;
    }

    // ------------------------------------------------------------------- storage

    private void openStorage(Player p, Entity furniture, DecorModule.Def d) {
        if (!mayUse(p, furniture)) return;
        Entity box = decor.hitboxOf(furniture);
        UUID id = box.getUniqueId();
        Inventory inv = open.get(id);
        if (inv == null) {
            int rows = Math.max(1, Math.min(6, d.storageRows()));
            StorageHolder h = new StorageHolder(id);
            inv = Bukkit.createInventory(h, rows * 9, mm(d.name()));
            h.inv = inv;
            byte[] data = box.getPersistentDataContainer().get(itemsKey, PersistentDataType.BYTE_ARRAY);
            if (data != null) {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(data);
                for (int i = 0; i < items.length; i++) {
                    if (items[i] == null) continue;
                    if (i < inv.getSize()) {
                        inv.setItem(i, items[i]);
                    } else {   // the config shrank this piece of furniture: spill instead of deleting items
                        box.getWorld().dropItemNaturally(box.getLocation().add(0, 0.5, 0), items[i]);
                    }
                }
            }
            open.put(id, inv);
        }
        p.openInventory(inv);
        box.getWorld().playSound(box.getLocation().add(0, 0.5, 0), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.15f);
        plugin.quest(p, "STORE", d.id());
    }

    void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof StorageHolder h)) return;
        Entity box = Bukkit.getEntity(h.box);
        if (box != null && box.isValid()) {
            save(box, e.getInventory());
            box.getWorld().playSound(box.getLocation().add(0, 0.5, 0), Sound.BLOCK_BARREL_CLOSE, 0.6f, 1.15f);
        } else {
            // the furniture is gone: never swallow what was inside
            Location at = e.getPlayer().getLocation();
            for (ItemStack it : e.getInventory().getContents()) {
                if (it != null && !it.getType().isAir()) at.getWorld().dropItemNaturally(at, it);
            }
            e.getInventory().clear();
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (h.inv != null && h.inv.getViewers().isEmpty()) open.remove(h.box);
        });
    }

    private void save(Entity box, Inventory inv) {
        byte[] bytes = ItemStack.serializeItemsAsBytes(inv.getContents());
        box.getPersistentDataContainer().set(itemsKey, PersistentDataType.BYTE_ARRAY, bytes);
    }

    void closeAll() {
        for (Inventory inv : new ArrayList<>(open.values())) {
            for (HumanEntity he : new ArrayList<>(inv.getViewers())) he.closeInventory();
        }
        open.clear();
    }

    // -------------------------------------------------------------- placed / removed

    void onPlaced(Entity box, DecorModule.Def d) {
        if ("pot".equals(d.station()) && plugin.cooking() != null) {
            Block home = decor.homeBlockOf(box);
            if (home != null) plugin.cooking().removePot(home);   // clears any stale pot row for this cell
        }
    }

    /** Called before a piece of furniture is deleted: spill its storage, end its pot, close open GUIs. */
    void beforeRemove(Entity furniture, Entity peer, DecorModule.Def d, Location loc) {
        Entity box = furniture instanceof Interaction ? furniture : (peer instanceof Interaction ? peer : furniture);
        UUID id = box.getUniqueId();
        Inventory inv = open.get(id);
        if (inv != null) {
            for (HumanEntity he : new ArrayList<>(inv.getViewers())) he.closeInventory();   // saves through onClose
        }
        PersistentDataContainer pdc = box.getPersistentDataContainer();
        byte[] data = pdc.get(itemsKey, PersistentDataType.BYTE_ARRAY);
        if (data != null) {
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(data)) {
                if (it != null && !it.getType().isAir()) {
                    loc.getWorld().dropItemNaturally(loc.clone().add(0, 0.5, 0), it);
                }
            }
            pdc.remove(itemsKey);
        }
        open.remove(id);
        litStoves.remove(id);
        if ("pot".equals(d.station()) && plugin.cooking() != null) {
            Block home = decor.homeBlockOf(box);
            if (home != null) plugin.cooking().removePot(home);
        }
    }

    /** A chunk's furniture loaded: stoves that are still burning get their flames back. */
    void track(Entity en) {
        if (!(en instanceof Interaction)) return;
        DecorModule.Def d = decor.defOf(en);
        if (d != null && "stove".equals(d.station()) && stoveLit(en)) litStoves.add(en.getUniqueId());
    }

    // ----------------------------------------------------------------------- stove

    private boolean stoveLit(Entity box) {
        Long until = box.getPersistentDataContainer().get(stoveKey, PersistentDataType.LONG);
        return until != null && until > System.currentTimeMillis();
    }

    private int fuelSeconds(Material m) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("stove.fuel-seconds");
        if (s == null) return 0;
        if (s.contains(m.name())) return s.getInt(m.name());
        String n = m.name();
        if (n.endsWith("_LOG") || n.endsWith("_WOOD")) return s.getInt("LOGS", 0);
        if (n.endsWith("_PLANKS")) return s.getInt("PLANKS", 0);
        return 0;
    }

    private void useStove(Player p, Entity furniture) {
        Entity box = decor.hitboxOf(furniture);
        PersistentDataContainer pdc = box.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        long maxMs = plugin.getConfig().getInt("stove.max-seconds", 600) * 1000L;
        ItemStack hand = p.getInventory().getItemInMainHand();
        Material hm = hand.getType();
        boolean lit = stoveLit(box);
        Location at = box.getLocation().add(0, 0.9, 0);

        if (hm.isAir()) {
            if (lit) {
                long left = pdc.get(stoveKey, PersistentDataType.LONG) - now;
                p.sendActionBar(mm("<gold>\uD83D\uDD25 The stove is burning <gray>for another <white>" + fmt(left)
                        + "<gray> - right-click raw meat or fish to grill it."));
            } else {
                p.sendActionBar(mm("<gray>The stove is cold. Feed it coal, charcoal or wood."));
            }
            return;
        }
        if (plugin.isHomesteadItem(hand)) {
            p.sendActionBar(mm("<red>That doesn't belong in a stove."));
            return;
        }

        int secs = fuelSeconds(hm);
        if (secs > 0) {
            long base = Math.max(now, lit ? pdc.get(stoveKey, PersistentDataType.LONG) : now);
            if (base - now >= maxMs) {
                p.sendActionBar(mm("<yellow>The stove is already full of fuel."));
                return;
            }
            long until = Math.min(now + maxMs, base + secs * 1000L);
            pdc.set(stoveKey, PersistentDataType.LONG, until);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            litStoves.add(box.getUniqueId());
            box.getWorld().playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.0f);
            box.getWorld().spawnParticle(Particle.FLAME, at, 8, 0.15, 0.05, 0.15, 0.02);
            p.sendActionBar(mm("<gold>\uD83D\uDD25 Stove lit <gray>for <white>" + fmt(until - now)));
            return;
        }

        ConfigurationSection grill = plugin.getConfig().getConfigurationSection("stove.grill");
        String out = grill == null ? null : grill.getString(hm.name());
        Material result = out == null ? null : Material.matchMaterial(out);
        if (result == null) {
            p.sendActionBar(mm("<red>✖ The stove can't use that."));
            return;
        }
        if (!lit) {
            p.sendActionBar(mm("<yellow>Light the stove first <gray>(coal, charcoal or wood)."));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        long delay = Math.max(1, plugin.getConfig().getInt("stove.grill-seconds", 3)) * 20L;
        box.getWorld().playSound(at, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1.0f, 1.0f);
        box.getWorld().spawnParticle(Particle.SMOKE, at, 6, 0.15, 0.05, 0.15, 0.01);
        p.sendActionBar(mm("<gold>♨ Grilling..."));
        UUID who = p.getUniqueId();
        Location drop = at.clone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack cooked = new ItemStack(result);
            Player pl = Bukkit.getPlayer(who);
            if (pl != null && pl.isOnline()) {
                for (ItemStack left : pl.getInventory().addItem(cooked).values()) {
                    drop.getWorld().dropItemNaturally(drop, left);
                }
                pl.playSound(pl.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
                plugin.quest(pl, "GRILL", result.name());
            } else {
                drop.getWorld().dropItemNaturally(drop, cooked);
            }
            drop.getWorld().playSound(drop, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.6f);
        }, delay);
    }

    /** Once a second: flames, crackle and warmth for every burning stove. */
    void tickStoves() {
        SurvivalModule sv = plugin.survival();
        Iterator<UUID> it = litStoves.iterator();
        while (it.hasNext()) {
            Entity box = Bukkit.getEntity(it.next());
            if (box == null || !box.isValid() || !stoveLit(box)) {
                it.remove();
                continue;
            }
            Location l = box.getLocation().add(0, 0.9, 0);
            l.getWorld().spawnParticle(Particle.FLAME, l, 2, 0.18, 0.02, 0.18, 0.004);
            if (rng.nextDouble() < 0.5) l.getWorld().spawnParticle(Particle.SMOKE, l.clone().add(0, 0.3, 0), 1, 0.1, 0.0, 0.1, 0.01);
            if (rng.nextDouble() < 0.3) l.getWorld().playSound(l, Sound.BLOCK_FIRE_AMBIENT, 0.35f, 1.0f);
            if (sv != null) {
                for (Player pl : l.getWorld().getNearbyPlayers(l, 5.0)) sv.warm(pl, 6);
            }
        }
    }

    // ------------------------------------------------------------------------- pot

    private void usePot(Player p, Entity furniture) {
        CookingModule cook = plugin.cooking();
        if (cook == null) {
            p.sendActionBar(mm("<red>Cooking is disabled."));
            return;
        }
        Block home = decor.homeBlockOf(decor.hitboxOf(furniture));
        if (home == null) return;
        if (cook.needsHeat(home) && !heated(home)) {
            p.sendActionBar(mm("<red>\uD83D\uDD25 The pot needs heat:</red> <gray>set it on a lit campfire or furnace, or beside a lit stove."));
            return;
        }
        cook.interactPot(p, home);
    }

    private boolean litBlock(Block b) {
        Material m = b.getType();
        if (m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE && m != Material.FURNACE
                && m != Material.BLAST_FURNACE && m != Material.SMOKER) return false;
        return b.getBlockData() instanceof Lightable l && l.isLit();
    }

    private boolean heated(Block home) {
        if (litBlock(home.getRelative(BlockFace.DOWN))) return true;
        Location c = home.getLocation().add(0.5, 0.5, 0.5);
        for (Entity en : home.getWorld().getNearbyEntities(c, 1.6, 1.0, 1.6)) {
            if (!(en instanceof Interaction)) continue;
            DecorModule.Def d = decor.defOf(en);
            if (d != null && "stove".equals(d.station()) && stoveLit(en)) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- cutting board

    public ItemStack createKnife() {
        ItemStack it = new ItemStack(Material.IRON_HOE);
        ItemMeta m = it.getItemMeta();
        m.displayName(item("<white>Kitchen Knife"));
        m.lore(List.of(item("<gray>Knife in one hand, ingredient in the other,"),
                item("<gray>then right-click a <white>Cutting Board"),
                item("<dark_gray>Wears down as you chop")));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(knifeKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isKnife(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(knifeKey, PersistentDataType.BYTE);
    }

    private void useBoard(Player p, Entity furniture) {
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        ItemStack knife;
        ItemStack food;
        if (isKnife(main)) {
            knife = main;
            food = off;
        } else if (isKnife(off)) {
            knife = off;
            food = main;
        } else {
            p.sendActionBar(mm("<gray>You need a <white>Kitchen Knife<gray> to chop here."));
            return;
        }
        if (food == null || food.getType().isAir()) {
            p.sendActionBar(mm("<gray>Hold the knife in one hand and an ingredient in the other."));
            return;
        }
        if (plugin.isHomesteadItem(food)) {
            p.sendActionBar(mm("<red>✖ You can't chop that."));
            return;
        }
        Material in = food.getType();
        String spec = plugin.getConfig().getString("cutting-board.recipes." + in.name());
        Material result = null;
        int amount = 1;
        if (spec != null) {
            String[] parts = spec.split(":");
            result = Material.matchMaterial(parts[0]);
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
        }
        if (result == null) {
            p.sendActionBar(mm("<red>✖ Nothing to chop there."));
            return;
        }

        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        if (!creative) food.setAmount(food.getAmount() - 1);
        if (!creative && knife.getItemMeta() instanceof Damageable dm) {
            dm.setDamage(dm.getDamage() + 1);
            knife.setItemMeta(dm);
            if (dm.getDamage() >= knife.getType().getMaxDurability()) {
                knife.setAmount(0);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
                p.sendActionBar(mm("<red>Your knife broke!"));
            }
        }
        Location at = furniture.getLocation().add(0, 0.15, 0);
        at.getWorld().spawnParticle(Particle.ITEM, at, 10, 0.12, 0.05, 0.12, 0.05, new ItemStack(in));
        at.getWorld().playSound(at, Sound.ITEM_AXE_STRIP, 0.7f, 1.7f);
        for (ItemStack left : p.getInventory().addItem(new ItemStack(result, amount)).values()) {
            at.getWorld().dropItemNaturally(at, left);
        }
        plugin.quest(p, "CHOP", in.name());
    }

    // ---------------------------------------------------------------------- misc

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }
}
