package dev.laxus.homestead.backpack;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
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
 * Portable storage with skins, shown on the player's back.
 *  - Right-click a backpack to open it. Contents are stored inside the item itself.
 *  - While a backpack is anywhere in your inventory (not in your hands) a 3D model is shown on your back.
 *  - Craft: leather x7 + string + chest. Re-skin: backpack + dye in the crafting grid (contents are kept).
 */
public final class BackpackModule extends AbstractModule {

    public record Skin(String id, String name, Material base, int cmd) {}

    private static final class Holder implements InventoryHolder {
        final String id;
        Inventory inv;

        Holder(String id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final String DYE_PREFIX = "backpack_skin_";

    private final Map<String, Skin> skins = new LinkedHashMap<>();
    private final Map<String, String> dyeRecipes = new HashMap<>();   // recipe key -> skin id
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();
    private final Map<UUID, ItemDisplay> worn = new HashMap<>();
    private final Map<UUID, String> wornSkin = new HashMap<>();
    private final Map<UUID, Float> bodyYaw = new HashMap<>();      // simulated body rotation (head yaw is NOT the body)
    private final Map<UUID, double[]> lastXZ = new HashMap<>();

    private final NamespacedKey markerKey;
    private final NamespacedKey idKey;
    private final NamespacedKey dataKey;
    private final NamespacedKey skinKey;
    private final NamespacedKey recipeKey;

    public BackpackModule(HomesteadPlugin plugin) {
        super(plugin);
        markerKey = new NamespacedKey(plugin, "backpack");
        idKey = new NamespacedKey(plugin, "backpack_id");
        dataKey = new NamespacedKey(plugin, "backpack_data");
        skinKey = new NamespacedKey(plugin, "backpack_skin");
        recipeKey = new NamespacedKey(plugin, "backpack_recipe");
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onEnable() {
        loadSkins();

        ShapedRecipe r = new ShapedRecipe(recipeKey, createBackpack(defaultSkin()));
        r.shape("LSL", "LCL", "LLL");
        r.setIngredient('L', Material.LEATHER);
        r.setIngredient('S', Material.STRING);
        r.setIngredient('C', Material.CHEST);
        Bukkit.addRecipe(r);
        recipeKeys.add(recipeKey);

        ConfigurationSection dyes = plugin.getConfig().getConfigurationSection("backpack.skin-dyes");
        if (dyes != null) {
            for (String dye : dyes.getKeys(false)) {
                Material dm = Material.matchMaterial(dye);
                String skin = dyes.getString(dye, "");
                if (dm == null || !skins.containsKey(skin)) {
                    plugin.getLogger().warning("Backpack skin-dyes: bad entry '" + dye + "'");
                    continue;
                }
                NamespacedKey k = new NamespacedKey(plugin, DYE_PREFIX + dye.toLowerCase());
                ShapelessRecipe sr = new ShapelessRecipe(k, createBackpack(skin));
                sr.addIngredient(new RecipeChoice.MaterialChoice(baseMaterial()));
                sr.addIngredient(dm);
                Bukkit.addRecipe(sr);
                recipeKeys.add(k);
                dyeRecipes.put(k.getKey(), skin);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (NamespacedKey k : recipeKeys) p.discoverRecipe(k);
        }
        every(1L, this::updateWorn);
    }

    @Override
    protected void beforeDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }

    @Override
    protected void onDisable() {
        for (ItemDisplay d : worn.values()) d.remove();
        worn.clear();
        wornSkin.clear();
        bodyYaw.clear();
        lastXZ.clear();
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
        dyeRecipes.clear();
        skins.clear();
    }

    private void loadSkins() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("backpack.skins");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Material base = Material.matchMaterial(s.getString("base", baseMaterial().name()));
                skins.put(id, new Skin(id, s.getString("name", "<gold>Backpack"),
                        base == null ? baseMaterial() : base, s.getInt("cmd", 0)));
            }
        }
        if (skins.isEmpty()) skins.put("classic", new Skin("classic", "<gold>Backpack", baseMaterial(), 0));
        plugin.getLogger().info("Loaded " + skins.size() + " backpack skin(s).");
    }

    private Material baseMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("backpack.base", "LEATHER"));
        return m == null ? Material.LEATHER : m;
    }

    private String defaultSkin() {
        String d = plugin.getConfig().getString("backpack.default-skin", "classic");
        return skins.containsKey(d) ? d : skins.keySet().iterator().next();
    }

    public List<String> skinIds() {
        return new ArrayList<>(skins.keySet());
    }

    // ----------------------------------------------------------------- items

    public ItemStack createBackpack() {
        return createBackpack(defaultSkin());
    }

    public ItemStack createBackpack(String skinId) {
        Skin s = skins.getOrDefault(skinId, skins.get(defaultSkin()));
        ItemStack it = new ItemStack(s.base());
        ItemMeta m = it.getItemMeta();
        m.lore(List.of(item("<gray>Right-click to open"), item("<dark_gray>Keeps its contents when dropped"),
                item("<dark_gray>Combine with a dye to change its look")));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        applySkin(it, s);
        return it;
    }

    private void applySkin(ItemStack it, Skin s) {
        ItemMeta m = it.getItemMeta();
        m.displayName(item(s.name()));
        if (s.cmd() > 0) m.setCustomModelData(s.cmd());
        m.getPersistentDataContainer().set(skinKey, PersistentDataType.STRING, s.id());
        it.setItemMeta(m);
    }

    private boolean isBackpack(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private String skinOf(ItemStack it) {
        String id = it.getItemMeta().getPersistentDataContainer().get(skinKey, PersistentDataType.STRING);
        return id != null && skins.containsKey(id) ? id : defaultSkin();
    }

    private boolean viewingBackpack(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    // --------------------------------------------------------- worn on back

    private ItemStack findWorn(Player p) {
        PlayerInventory pi = p.getInventory();
        int held = pi.getHeldItemSlot();
        for (int i = 0; i < 36; i++) {
            if (i == held) continue;
            ItemStack it = pi.getItem(i);
            if (isBackpack(it)) return it;
        }
        return null;
    }

    private void updateWorn() {
        boolean show = plugin.getConfig().getBoolean("backpack.show-on-back", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            ItemStack bp = show ? findWorn(p) : null;
            boolean visible = bp != null && !p.isDead() && !p.isInvisible()
                    && p.getGameMode() != GameMode.SPECTATOR && plugin.worldAllowed(p.getWorld())
                    && !(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder);
            if (!visible) {
                removeWorn(id);
                continue;
            }
            String skinId = skinOf(bp);
            ItemDisplay d = worn.get(id);
            if (d == null || !d.isValid() || !p.equals(d.getVehicle())) {
                removeWorn(id);
                d = spawnWorn(p);
                worn.put(id, d);
            }
            if (!skinId.equals(wornSkin.get(id))) {
                Skin s = skins.get(skinId);
                ItemStack shown = new ItemStack(s.base());
                ItemMeta m = shown.getItemMeta();
                if (s.cmd() > 0) m.setCustomModelData(s.cmd());
                shown.setItemMeta(m);
                d.setItemStack(shown);
                wornSkin.put(id, skinId);
            }
            d.setRotation(updateBodyYaw(p), 0f);

            // Hide the pack from its own wearer while they look steeply down, so it doesn't fill their screen.
            // Other players still see it.
            double limit = plugin.getConfig().getDouble("backpack.hide-when-looking-down", 0.0);
            boolean hideSelf = limit > 0 && p.getLocation().getPitch() > limit;
            if (hideSelf && !p.canSee(d)) { /* already hidden */ }
            else if (hideSelf) p.hideEntity(plugin, d);
            else if (!p.canSee(d)) p.showEntity(plugin, d);
        }
    }

    /**
     * Mimics vanilla body rotation: the body only follows the head while walking, or when the head
     * is turned more than 75 degrees away from it. Standing still and looking around leaves the pack put.
     */
    private float updateBodyYaw(Player p) {
        UUID id = p.getUniqueId();
        float head = p.getLocation().getYaw();
        float body = bodyYaw.getOrDefault(id, head);

        double px = p.getLocation().getX(), pz = p.getLocation().getZ();
        double[] last = lastXZ.get(id);
        boolean moving = last != null && (px - last[0]) * (px - last[0]) + (pz - last[1]) * (pz - last[1]) > 0.0004;
        lastXZ.put(id, new double[]{px, pz});

        float diff = wrap180(head - body);
        if (moving) {
            body += diff * 0.4f;                       // ease towards the look direction while walking
        } else if (Math.abs(diff) > 75f) {
            body = head - Math.signum(diff) * 75f;     // head turned too far: drag the body along
        }
        body = wrap180(body);
        bodyYaw.put(id, body);
        return body;
    }

    private static float wrap180(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private ItemDisplay spawnWorn(Player p) {
        double x = plugin.getConfig().getDouble("backpack.back.x", 0.0);
        double y = plugin.getConfig().getDouble("backpack.back.y", -0.80);
        double z = plugin.getConfig().getDouble("backpack.back.z", -0.27);
        float sc = (float) plugin.getConfig().getDouble("backpack.back.scale", 1.0);
        ItemDisplay d = p.getWorld().spawn(p.getLocation(), ItemDisplay.class, disp -> {
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setBillboard(Display.Billboard.FIXED);
            disp.setPersistent(false);
            disp.setInvulnerable(true);
            disp.setTransformation(new Transformation(new Vector3f((float) x, (float) y, (float) z),
                    new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
        });
        p.addPassenger(d);
        return d;
    }

    private void removeWorn(UUID id) {
        ItemDisplay d = worn.remove(id);
        wornSkin.remove(id);
        bodyYaw.remove(id);
        lastXZ.remove(id);
        if (d != null) d.remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeWorn(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        removeWorn(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        removeWorn(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- open/save

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (!isBackpack(hand)) return;
        Player p = e.getPlayer();
        org.bukkit.block.Block clicked = e.getClickedBlock();
        // chests, doors, crafting tables... still work while a backpack is in hand (sneak to open the pack on them)
        if (clicked != null && !p.isSneaking() && clicked.getType().isInteractable()) return;
        e.setCancelled(true);
        if (!plugin.worldAllowed(p.getWorld()) || viewingBackpack(p)) return;
        open(p, hand);
    }

    private void open(Player p, ItemStack stack) {
        ItemMeta m = stack.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        String id = pdc.get(idKey, PersistentDataType.STRING);
        if (id == null) {
            id = UUID.randomUUID().toString();
            pdc.set(idKey, PersistentDataType.STRING, id);
            stack.setItemMeta(m);
        }
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("backpack.rows", 3)));
        Holder h = new Holder(id);
        Inventory inv = Bukkit.createInventory(h, rows * 9, mm(skins.get(skinOf(stack)).name()));
        h.inv = inv;

        byte[] data = pdc.get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (data != null) {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(data);
            for (int i = 0; i < Math.min(items.length, inv.getSize()); i++) inv.setItem(i, items[i]);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        ItemStack[] contents = e.getInventory().getContents();
        byte[] bytes = ItemStack.serializeItemsAsBytes(contents);

        PlayerInventory pi = p.getInventory();
        for (int i = 0; i < pi.getSize(); i++) {
            ItemStack it = pi.getItem(i);
            if (!isBackpack(it)) continue;
            ItemMeta m = it.getItemMeta();
            if (!h.id.equals(m.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))) continue;
            m.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY, bytes);
            ItemStack copy = it.clone();
            copy.setItemMeta(m);
            pi.setItem(i, copy);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.8f);
            return;
        }
        // The backpack item is gone (e.g. died while it was open): spill the contents instead of losing them.
        for (ItemStack c : contents) {
            if (c != null) p.getWorld().dropItemNaturally(p.getLocation(), c);
        }
    }

    // --------------------------------------------------------------- skins

    /** backpack + dye: keep the real backpack (contents, id) and only change its skin. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k)) return;
        String skinId = dyeRecipes.get(k.getKey().getKey());
        if (skinId == null) {
            // any other recipe would eat the backpack as plain leather - and its contents with it
            for (ItemStack it : e.getInventory().getMatrix()) {
                if (isBackpack(it)) {
                    e.getInventory().setResult(null);
                    return;
                }
            }
            return;
        }
        ItemStack source = null;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isBackpack(it)) {
                source = it;
                break;
            }
        }
        if (source == null) {
            e.getInventory().setResult(null);   // plain leather + dye is not a backpack
            return;
        }
        ItemStack result = source.clone();
        applySkin(result, skins.get(skinId));
        e.getInventory().setResult(result);
    }

    // ----------------------------------------------------------- protections

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isBackpack(e.getCurrentItem()) || isBackpack(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY && isBackpack(p.getInventory().getItem(e.getHotbarButton()))) {
            e.setCancelled(true);
        } else if (e.getClick() == ClickType.SWAP_OFFHAND && isBackpack(p.getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder && isBackpack(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (viewingBackpack(e.getPlayer()) && isBackpack(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (NamespacedKey k : recipeKeys) e.getPlayer().discoverRecipe(k);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(recipeKey) && e.getWhoClicked() instanceof Player p) {
            plugin.quest(p, "CRAFT", "backpack");
        }
    }
}
