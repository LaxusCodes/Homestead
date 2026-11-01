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
import org.bukkit.event.world.EntitiesLoadEvent;
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
 * Ghost-furniture fix (v2):
 *  - An ItemDisplay with the NONE transform centres the model on its origin, so the display is
 *    lifted by half the (scaled) model height; the model now sits ON the floor, not in it.
 *  - An Interaction entity's position is the BOTTOM centre of its hitbox, so it is spawned at the
 *    floor and spans floor..floor+height - exactly over the visible model.
 *  - Display and hitbox are linked both ways, and picking up removes both.
 *  - Furniture placed by older versions is migrated when its chunk loads, and orphaned halves
 *    (a display without a hitbox or vice versa) are cleaned up.
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
    private final NamespacedKey versionKey;
    private static final int LAYOUT_VERSION = 2;

    public DecorModule(HomesteadPlugin plugin) {
        super(plugin);
        idKey = new NamespacedKey(plugin, "decor_id");
        ownerKey = new NamespacedKey(plugin, "decor_owner");
        displayKey = new NamespacedKey(plugin, "decor_display");
        interactKey = new NamespacedKey(plugin, "decor_interact");
        homeKey = new NamespacedKey(plugin, "decor_home");
        itemKey = new NamespacedKey(plugin, "decor_item");
        versionKey = new NamespacedKey(plugin, "decor_v");
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

    @EventHandler(ignoreCancelled = true)
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
        // stand on the real surface: slabs, carpets, soul sand... are lower than a full block
        Location ip = e.getInteractionPoint();
        if (ip != null && ip.getY() >= above.getY() - 1.0 && ip.getY() <= above.getY()) {
            target.setY(ip.getY());
        }
        target.setYaw(Math.round((p.getLocation().getYaw() + 180f) / 45f) * 45f);
        spawn(d, target, p.getUniqueId(), above);

        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        plugin.quest(p, "DECOR", id);
    }

    /** Local-space transform that stands the (centred) item model on the floor. */
    private Transformation transformFor(Def d) {
        float s = (float) d.scale();
        // model origin is its centre: lift by half its scaled height, plus the optional manual nudge
        float lift = 0.5f * s + (float) d.displayY();
        return new Transformation(
                new Vector3f(0f, lift, 0f),
                new AxisAngle4f(0f, 0f, 0f, 1f),
                new Vector3f(s, s, s),
                new AxisAngle4f(0f, 0f, 0f, 1f));
    }

    private void spawn(Def d, Location loc, UUID owner, Block homeBlock) {
        String home = blockKey(homeBlock.getLocation());

        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(createItem(d.id()));
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setBillboard(Display.Billboard.FIXED);
            disp.setShadowRadius(0f);
            disp.setInvulnerable(true);
            disp.setTransformation(transformFor(d));
        });
        tag(display, d.id(), owner, home);
        display.setRotation(loc.getYaw(), 0f);

        // Hitbox: an Interaction's position is the BOTTOM centre of its box, so it goes at the floor.
        Interaction interaction = loc.getWorld().spawn(loc, Interaction.class, in -> {
            in.setInteractionWidth((float) d.width());
            in.setInteractionHeight((float) d.height());
            in.setResponsive(true);
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
        pdc.set(versionKey, PersistentDataType.INTEGER, LAYOUT_VERSION);
    }

    // ------------------------------------------------------ click resolution

    /** Furniture is two linked entities: the ItemDisplay (visual) and the Interaction (hitbox). */
    private Entity peerOf(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        String key = e instanceof Interaction
                ? pdc.get(displayKey, PersistentDataType.STRING)
                : pdc.get(interactKey, PersistentDataType.STRING);
        if (key == null) return null;
        try {
            return Bukkit.getEntity(UUID.fromString(key));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** The hitbox half of a furniture piece (used as the stable seat anchor). */
    private Entity hitboxOf(Entity e) {
        if (e instanceof Interaction) return e;
        Entity peer = peerOf(e);
        return peer instanceof Interaction ? peer : e;
    }

    private Def defOf(Entity e) {
        String id = e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : defs.get(id);
    }

    // ------------------------------------------------------ sit / pick up

    @EventHandler(ignoreCancelled = true)
    public void onClickFurniture(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = e.getRightClicked();
        if (clicked == null || defOf(clicked) == null) return;
        e.setCancelled(true);
        interact(e.getPlayer(), clicked);
    }

    /** Right-clicking the block the furniture occupies/stands on still works. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (isFurnitureItem(e.getItem())) return;            // placing furniture is handled by onPlace
        Entity en = furnitureAt(b);                          // the block the furniture occupies
        if (en == null && e.getBlockFace() == BlockFace.UP) {
            // the TOP of the support block only - its sides stay free for building, and
            // chests / tables / doors under a rug keep working
            if (!p.isSneaking() && b.getType().isInteractable()) return;
            en = furnitureAt(b.getRelative(BlockFace.UP));
        }
        if (en == null) return;
        e.setCancelled(true);
        interact(p, en);
    }

    private boolean isFurnitureItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    private void interact(Player p, Entity furniture) {
        Def d = defOf(furniture);
        if (d == null || !plugin.worldAllowed(p.getWorld())) return;

        if (p.isSneaking()) {
            pickUp(p, furniture, d);
        } else if (d.seat()) {
            Entity target = hitboxOf(furniture);
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
        removeFurniture(furniture, d, p.getGameMode() != GameMode.CREATIVE);
    }

    /** Removes BOTH halves of a piece of furniture (and any seat on it), optionally dropping the item. */
    private void removeFurniture(Entity furniture, Def d, boolean drop) {
        Location loc = furniture.getLocation();
        Entity peer = peerOf(furniture);

        // clear any seats tied to either half, then remove BOTH halves (no leftover ghost model)
        for (Entity part : new Entity[]{furniture, peer}) {
            if (part == null) continue;
            ArmorStand seat = seatByFurniture.remove(part.getUniqueId());
            if (seat != null) seat.remove();
        }
        if (peer != null) peer.remove();
        furniture.remove();

        if (drop) {
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
        removeFurniture(en, d, e.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    /** Explosions that destroy the floor drop the furniture too, instead of leaving it floating. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        dropSupported(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        dropSupported(e.blockList());
    }

    private void dropSupported(List<Block> blocks) {
        for (Block blk : blocks) {
            Entity en = furnitureAt(blk.getRelative(BlockFace.UP));
            if (en == null) continue;
            Def d = defOf(en);
            if (d != null) removeFurniture(en, d, true);
        }
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
        sl.setY(sl.getY() + d.seatHeight());
        sl.setPitch(0f);
        Location from = p.getLocation();
        if (from.distanceSquared(sl) > 9.0) {
            p.sendActionBar(mm("<yellow>Walk closer to sit down."));
            return;
        }
        ArmorStand old = seats.remove(p.getUniqueId());      // moving to another seat: drop the old one
        if (old != null) {
            old.remove();
            seatByFurniture.values().remove(old);
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

    // ------------------------------------------------- migration & orphan cleanup

    /**
     * When a chunk's entities load: upgrade furniture placed by older versions (model half buried,
     * hitbox floating) and remove orphaned halves left behind by the old pick-up bug.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        List<Entity> mine = new ArrayList<>();
        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (Entity en : e.getEntities()) {
            if (en.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) {
                mine.add(en);
                present.add(en.getUniqueId());
            }
        }
        for (Entity en : mine) {
            PersistentDataContainer pdc = en.getPersistentDataContainer();
            String peerKey = en instanceof Interaction
                    ? pdc.get(displayKey, PersistentDataType.STRING)
                    : pdc.get(interactKey, PersistentDataType.STRING);
            boolean orphan;
            try {
                orphan = peerKey == null || !present.contains(UUID.fromString(peerKey));
            } catch (IllegalArgumentException ex) {
                orphan = true;
            }
            if (orphan) {
                en.remove();
                continue;
            }
            Integer v = pdc.get(versionKey, PersistentDataType.INTEGER);
            if (v != null && v >= LAYOUT_VERSION) continue;
            Def d = defOf(en);
            if (d == null) continue;
            if (en instanceof ItemDisplay disp) {
                disp.setTransformation(transformFor(d));
            } else if (en instanceof Interaction in) {
                // old hitboxes were spawned half their height above the floor
                in.teleport(floorOf(in));
                in.setInteractionWidth((float) d.width());
                in.setInteractionHeight((float) d.height());
                in.setResponsive(true);
            }
            pdc.set(versionKey, PersistentDataType.INTEGER, LAYOUT_VERSION);
        }
    }

    /** Floor position of an old-layout hitbox: its home block's Y at the same x/z. */
    private Location floorOf(Entity en) {
        String home = en.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        Location l = en.getLocation();
        if (home != null) {
            String[] p = home.split(",");
            if (p.length == 3) {
                try {
                    l.setY(Integer.parseInt(p[1]));
                } catch (NumberFormatException ignored) {
                    // keep current Y
                }
            }
        }
        return l;
    }

    // ----------------------------------------------------------------- helpers

    /** The furniture entity whose home block equals b, or null. */
    private Entity furnitureAt(Block b) {
        String k = blockKey(b.getLocation());
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 0.75, 1.25, 0.75)) {
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
