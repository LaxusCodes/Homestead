package dev.laxus.homestead.decor;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * Furniture built from an ItemDisplay (visual) plus an Interaction entity (hitbox).
 *
 * Fixes the "ghost furniture" issue: the visible model and the clickable hitbox used to
 * be offset from each other (the model floated half a block up). Now
 *  - the display renders the model anchored to the floor of its block, and
 *  - the Interaction hitbox is spawned at the vertical center of the visible model,
 * so what you see is exactly what you click.
 *
 * Place by right-clicking the top of a block with a furniture item. Sit by right-clicking.
 * Sneak + right-click to pick it up (owner or homestead.admin).
 * Clicking the block the furniture stands on/in also counts (fallback for rays that
 * miss the Interaction entity), and breaking the support block drops the furniture.
 */
public final class DecorModule extends AbstractModule {

    public record Def(String id, String name, Material material, int cmd, boolean seat,
                      double scale, double width, double height, double seatHeight, double displayY) {}

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private final Map<UUID, ArmorStand> seatByFurniture = new HashMap<>();
    private final NamespacedKey idKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey interactKey;
    private final NamespacedKey homeKey;
    private final NamespacedKey itemKey;

    public DecorModule(HomesteadPlugin plugin) {
        super(plugin);
        idKey = new NamespacedKey(plugin, "decor_id");
        ownerKey = new NamespacedKey(plugin, "decor_owner");
        displayKey = new NamespacedKey(plugin, "decor_display");
        interactKey = new NamespacedKey(plugin, "decor_interact");
        homeKey = new NamespacedKey(plugin, "decor_home");
        itemKey = new NamespacedKey(plugin, "decor_item");
    }

    @Override
    protected void onEnable() {
        loadDefs();
        every(10L, this::checkSeats);
    }

    @Override
    protected void onDisable() {
        for (ArmorStand a : new ArrayList<>(seats.values())) a.remove();
        seats.clear();
        seatByFurniture.clear();
        defs.clear();
    }

    private void loadDefs() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("decor.items");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Material m = Material.matchMaterial(s.getString("material", "PAPER"));
            if (m == null) {
                plugin.getLogger().warning("Decor '" + id + "': unknown material, skipping.");
                continue;
            }
            defs.put(id, new Def(id, s.getString("name", id), m, s.getInt("cmd", 0),
                    s.getBoolean("seat", false), s.getDouble("scale", 1.0), s.getDouble("width", 0.8),
                    s.getDouble("height", 0.8), s.getDouble("seat-height", 0.3),
                    s.getDouble("display-y", 0.0)));
        }
        plugin.getLogger().info("Loaded " + defs.size() + " furniture type(s).");
    }

    public List<String> ids() {
        return new ArrayList<>(defs.keySet());
    }

    public ItemStack createItem(String id) {
        Def d = defs.get(id);
        if (d == null) return null;
        ItemStack it = new ItemStack(d.material());
        ItemMeta m = it.getItemMeta();
        m.displayName(item(d.name()));
        m.lore(List.of(item("<gray>Furniture"), item("<dark_gray>Right-click the top of a block")));
        if (d.cmd() > 0) m.setCustomModelData(d.cmd());
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    // -------------------------------------------------------------- placing

    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getBlockFace() != BlockFace.UP || e.getClickedBlock() == null) return;
        ItemStack hand = e.getItem();
        if (hand == null || !hand.hasItemMeta()) return;
        String id = hand.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);

        Def d = defs.get(id);
        Player p = e.getPlayer();
        if (d == null || !plugin.worldAllowed(p.getWorld())) return;

        Block above = e.getClickedBlock().getRelative(BlockFace.UP);
        if (!above.isEmpty() && !above.isReplaceable()) return;
        if (furnitureAt(above) != null) {
            p.sendActionBar(mm("<red>Something already stands there."));
            return;
        }

        Location target = above.getLocation().add(0.5, 0.0, 0.5);
        target.setYaw(Math.round((p.getLocation().getYaw() + 180f) / 45f) * 45f);
        spawn(d, target, p.getUniqueId());

        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        plugin.quest(p, "DECOR", id);
    }

    private void spawn(Def d, Location loc, UUID owner) {
        float s = (float) d.scale();
        String home = blockKey(loc);

        // Visual: model anchored to the floor, scaled from its origin. Per-item `display-y`
        // shifts it if a particular model needs manual nudging.
        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(createItem(d.id()));
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setBillboard(Display.Billboard.FIXED);
            disp.setShadowRadius(0f);
            disp.setInvulnerable(true);
            disp.setTransformation(new Transformation(
                    new Vector3f(0f, (float) d.displayY(), 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(s, s, s),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
        tag(display, d.id(), owner, home);
        display.setRotation(loc.getYaw(), 0f);

        // Hitbox: vertical center of the visible volume so the box matches what you see.
        Location hit = loc.clone().add(0, d.height() / 2.0, 0);
        Interaction interaction = hit.getWorld().spawn(hit, Interaction.class, in -> {
            in.setInteractionWidth((float) d.width());
            in.setInteractionHeight((float) d.height());
        });
        tag(interaction, d.id(), owner, home);
        interaction.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, display.getUniqueId().toString());
        display.getPersistentDataContainer().set(interactKey, PersistentDataType.STRING, interaction.getUniqueId().toString());
        loc.getWorld().playSound(loc.clone().add(0, 0.3, 0), Sound.BLOCK_STONE_PLACE, 0.7f, 0.9f);
    }

    private void tag(Entity e, String id, UUID owner, String home) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, id);
        pdc.set(ownerKey, PersistentDataType.STRING, owner.toString());
        pdc.set(homeKey, PersistentDataType.STRING, home);
    }

    // ------------------------------------------------------ click resolution

    /** Furnitures linked both ways: each entity carries the other's UUID via these keys. */
    private Interaction pairInteraction(Entity furniture) {
        String key = furniture.getPersistentDataContainer().get(interactKey, PersistentDataType.STRING);
        if (key == null) return furniture instanceof Interaction in ? in : null;
        try {
            Entity en = Bukkit.getEntity(UUID.fromString(key));
            return en instanceof Interaction in ? in : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Def defOf(Entity e) {
        String id = e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : defs.get(id);
    }

    // ------------------------------------------------------ sit / pick up

    @EventHandler
    public void onClickFurniture(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = e.getRightClicked();
        if (clicked == null || defOf(clicked) == null) return;
        e.setCancelled(true);
        interact(e.getPlayer(), clicked);
    }

    /** Right-clicking the block the furniture occupies/stands on still works. */
    @EventHandler
    public void onBlockClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Entity en = furnitureAt(b) != null ? furnitureAt(b)
                : furnitureAt(b.getRelative(BlockFace.UP));   // clicked the support block
        if (en == null) return;
        e.setCancelled(true);
        interact(e.getPlayer(), en);
    }

    private void interact(Player p, Entity furniture) {
        Def d = defOf(furniture);
        if (d == null || !plugin.worldAllowed(p.getWorld())) return;

        if (p.isSneaking()) {
            pickUp(p, furniture, d);
        } else if (d.seat()) {
            Entity anchor = furniture instanceof ItemDisplay ? pairInteraction(furniture) : furniture;
            Entity target = anchor != null ? anchor : furniture;
            if (seatByFurniture.get(target.getUniqueId()) instanceof ArmorStand existing
                    && existing.isValid() && !existing.getPassengers().isEmpty()) {
                p.sendActionBar(mm("<yellow>Someone is already sitting there."));
                return;
            }
            sit(p, target, d);
        }
    }

    private void pickUp(Player p, Entity furniture, Def d) {
        PersistentDataContainer pdc = furniture.getPersistentDataContainer();
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (owner != null && !owner.equals(p.getUniqueId().toString()) && !p.hasPermission("homestead.admin")) {
            p.sendActionBar(mm("<red>That isn't yours."));
            return;
        }
        Location loc = furniture.getLocation();
        // remove the paired entity, clear its seat
        Interaction pair = pairInteraction(furniture);
        Entity other = pair != null ? pair : null;
        if (other == null) {
            String dispKey = pdc.get(displayKey, PersistentDataType.STRING);
            if (dispKey != null) {
                Entity en = null;
                try {
                    en = Bukkit.getEntity(UUID.fromString(dispKey));
                } catch (IllegalArgumentException ignored) {}
                other = en;
            }
        }
        if (other != null) {
            ArmorStand seat = seatByFurniture.remove(other.getUniqueId());
            if (seat != null) seat.remove();
            other.remove();
        }
        ArmorStand mySeat = seatByFurniture.remove(furniture.getUniqueId());
        if (mySeat != null) mySeat.remove();
        furniture.remove();
        if (p.getGameMode() != GameMode.CREATIVE) {
            loc.getWorld().dropItemNaturally(loc.clone().add(0, 0.5, 0), createItem(d.id()));
        }
        loc.getWorld().playSound(loc.clone().add(0, 0.4, 0), Sound.BLOCK_STONE_BREAK, 0.7f, 0.9f);
    }

    // --------------------------------------------------------- support breaking

    /** Breaking the block under a furniture drops the furniture instead of leaving it floating. */
    @EventHandler(ignoreCancelled = true)
    public void onBreakSupport(BlockBreakEvent e) {
        Entity en = furnitureAt(e.getBlock().getRelative(BlockFace.UP));
        if (en == null) return;
        Def d = defOf(en);
        if (d == null) return;
        pickUp(e.getPlayer(), en, d);
    }

    /** Attack-swing on furniture does nothing except a hint, and never damages it. */
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (defOf(e.getEntity()) == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) {
            p.sendActionBar(mm("<gray>Sneak + right-click to pick it up."));
        }
    }

    // ----------------------------------------------------------------- sitting

    private void sit(Player p, Entity furniture, Def d) {
        Location sl = furniture.getLocation();
        sl.setY(Math.floor(sl.getY()) + d.seatHeight());
        sl.setPitch(0f);
        Location from = p.getLocation();
        if (from.distanceSquared(sl) > 9.0) {
            p.sendActionBar(mm("<yellow>Walk closer to sit down."));
            return;
        }
        ArmorStand stand = furniture.getWorld().spawn(sl, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
        });
        stand.addPassenger(p);
        seats.put(p.getUniqueId(), stand);
        seatByFurniture.put(furniture.getUniqueId(), stand);
    }

    /** Cleans up seat entities once the rider has dismounted or left. */
    private void checkSeats() {
        for (Map.Entry<UUID, ArmorStand> en : new ArrayList<>(seats.entrySet())) {
            ArmorStand a = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !a.isValid() || a.getPassengers().isEmpty()) {
                a.remove();
                seats.remove(en.getKey());
                seatByFurniture.values().remove(a);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ArmorStand a = seats.remove(e.getPlayer().getUniqueId());
        if (a != null) {
            a.remove();
            seatByFurniture.values().remove(a);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** The furniture entity whose home block equals b, or null. */
    private Entity furnitureAt(Block b) {
        String k = blockKey(b.getLocation());
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 0.75, 0.75, 0.75)) {
            PersistentDataContainer pdc = en.getPersistentDataContainer();
            if (!pdc.has(idKey, PersistentDataType.STRING)) continue;
            String home = pdc.get(homeKey, PersistentDataType.STRING);
            if (k.equals(home)) return en;
        }
        return null;
    }

    private String blockKey(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
