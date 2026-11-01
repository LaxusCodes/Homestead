package dev.laxus.homestead.ui;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.cooking.CookingModule;
import dev.laxus.homestead.wine.WineModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static dev.laxus.homestead.Text.item;
import static dev.laxus.homestead.Text.mm;

/**
 * The in-game UI: quest menu (/quests) and recipe book (/homestead recipes).
 * Both are view-only chat-free menus with useful lore; clicking anything only
 * plays a soft sound (no item theft).
 */
public final class HomesteadMenus extends AbstractModule {

    public static final class MenuHolder implements InventoryHolder {
        final String id;
        Inventory inv;

        MenuHolder(String id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public HomesteadMenus(HomesteadPlugin plugin) {
        super(plugin);
    }

    @Override
    protected void onEnable() {
        // nothing scheduled; menus are opened on demand
    }

    public boolean isMenu(Inventory inv) {
        return inv.getHolder() instanceof MenuHolder;
    }

    // ----------------------------------------------------------------- quests

    public void openQuests(Player p) {
        MenuHolder h = new MenuHolder("quests");
        Inventory inv = Bukkit.createInventory(h, 27, mm("<dark_green>Homestead Quests"));
        h.inv = inv;

        List<String[]> items = plugin.quests() != null ? plugin.quests().menuItems(p) : List.of();
        int slot = 0;
        for (String[] q : items) {
            String id = q[0];
            String name = q[1];
            String type = q[2];
            String desc = q[3];
            boolean done = q[4].equals("1");
            boolean open = q[5].equals("1");
            int have = Integer.parseInt(q[6]);
            int need = Integer.parseInt(q[7]);

            ItemStack icon;
            if (done) icon = new ItemStack(Material.LIME_WOOL);
            else if (open) icon = iconFor(type);
            else icon = new ItemStack(Material.GRAY_DYE);

            ItemMeta m = icon.getItemMeta();
            m.displayName(item(done ? "<green>" + name : open ? "<yellow>" + name : "<dark_gray>" + name));
            List<String> lore = new ArrayList<>();
            lore.add(done ? "<green>Completed ✓" : open ? "<gray>" + desc : "<dark_gray>Locked");
            if (!done && open) {
                int filled = Math.min(10, (int) Math.round(10.0 * have / Math.max(1, need)));
                lore.add("<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(10 - filled)
                        + " <gray>" + have + "/" + need);
                lore.add("<dark_gray>Progress: <green>" + progressWord(filled));
            }
            m.lore(lore.stream().map(ll -> mm(ll).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
            icon.setItemMeta(m);
            if (slot < inv.getSize()) inv.setItem(slot++, icon);
        }

        // a small filler glass pane behind
        ItemStack glass = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.displayName(item("<dark_gray>"));
        glass.setItemMeta(gm);
        while (slot < inv.getSize()) inv.setItem(slot, glass);

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
    }

    private ItemStack iconFor(String type) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "BREAK" -> new ItemStack(Material.IRON_PICKAXE);
            case "HARVEST" -> new ItemStack(Material.WHEAT);
            case "KILL" -> new ItemStack(Material.IRON_SWORD);
            case "CRAFT" -> new ItemStack(Material.CRAFTING_TABLE);
            case "COOK" -> new ItemStack(Material.MUSHROOM_STEW);
            case "EAT" -> new ItemStack(Material.COOKED_BEEF);
            case "DRINK" -> new ItemStack(Material.WATER_BUCKET);
            case "DECOR" -> new ItemStack(Material.PAINTING);
            case "BREW" -> new ItemStack(Material.HONEY_BOTTLE);
            case "FIND" -> new ItemStack(Material.ECHO_SHARD);
            default -> new ItemStack(Material.BOOK);
        };
    }

    private String progressWord(int filled) {
        if (filled >= 8) return "almost done";
        if (filled >= 5) return "over halfway";
        if (filled >= 1) return "going well";
        return "just started";
    }

    // ---------------------------------------------------------------- recipes

    public void openRecipes(Player p) {
        MenuHolder h = new MenuHolder("recipes");
        Inventory inv = Bukkit.createInventory(h, 45, mm("<dark_red>Homestead Recipe Book"));
        h.inv = inv;
        int slot = 0;

        // cooking recipes
        CookingModule c = plugin.cooking();
        if (c != null) {
            for (String[] entry : c.menuEntries()) {
                String id = entry[0];
                ItemStack icon = c.createMeal(id);
                if (icon == null) icon = new ItemStack(Material.BOWL);
                ItemMeta m = icon.getItemMeta();
                m.lore(lore(entry[1]));
                icon.setItemMeta(m);
                if (slot < inv.getSize()) inv.setItem(slot++, icon);
            }
        }

        // wine info
        WineModule wine = plugin.wine();
        if (wine != null) {
            ItemStack mustInfo = new ItemStack(Material.GLASS_BOTTLE);
            ItemMeta mm2 = mustInfo.getItemMeta();
            mm2.displayName(item("<light_purple>Must Bottles"));
            mm2.lore(lore("<gray>Cook a must in the pot (fruit + sugar) —<dark_gray> served in bottles."));
            mustInfo.setItemMeta(mm2);

            ItemStack barrelInfo = new ItemStack(Material.BARREL);
            ItemMeta bm = barrelInfo.getItemMeta();
            bm.displayName(item("<gold>Barrel Fermentation"));
            bm.lore(lore("<gray>Open any barrel and put must bottles inside.", " <dark_gray>The timer starts when they're placed in.", " <dark_gray>When it's done they become wine by themselves."));
            barrelInfo.setItemMeta(bm);
            if (slot + 1 < inv.getSize()) {
                inv.setItem(slot++, mustInfo);
                inv.setItem(slot++, barrelInfo);
            }

            ItemStack wineInfo = new ItemStack(Material.HONEY_BOTTLE);
            ItemMeta wm = wineInfo.getItemMeta();
            wm.displayName(item("<dark_purple>Wines"));
            wm.lore(lore("<gray>Drinking wine grants warmth and small boosts,", " <dark_gray>but the Nausea stacks if you keep drinking."));
            wineInfo.setItemMeta(wm);
            if (slot < inv.getSize()) inv.setItem(slot++, wineInfo);
        }

        // recipes + furniture hint
        ItemStack furnitureInfo = new ItemStack(Material.OAK_STAIRS);
        ItemMeta fm = furnitureInfo.getItemMeta();
        fm.displayName(item("<gold>Furniture"));
        fm.lore(lore("<gray>All furniture is craftable in a crafting table.", " <dark_gray>Right-click the top of a block to place,", " <dark_gray>right-click to sit, sneak + right-click to pick up."));
        furnitureInfo.setItemMeta(fm);
        if (slot < inv.getSize()) inv.setItem(slot++, furnitureInfo);

        ItemStack backpackInfo = new ItemStack(Material.LEATHER);
        ItemMeta bp = backpackInfo.getItemMeta();
        bp.displayName(item("<gold>Backpack"));
        bp.lore(lore("<gray>7x leather + string + chest (top row LSL)", " <dark_gray>Combine it with a dye to change its skin."));
        backpackInfo.setItemMeta(bp);
        if (slot < inv.getSize()) inv.setItem(slot++, backpackInfo);

        ItemStack deco = new ItemStack(Material.PAPER);
        ItemMeta dm = deco.getItemMeta();
        dm.displayName(item("<gray>Quests & Cooking"));
        dm.lore(lore("<gray>/quests  - quest progress", " <gray>Right-click a cauldron on a lit campfire while", " <dark_gray>holding ingredients, stir with a stick, serve with a bowl."));
        deco.setItemMeta(dm);
        if (slot < inv.getSize()) inv.setItem(slot++, deco);

        ItemStack edge = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        ItemMeta em = edge.getItemMeta();
        em.displayName(item("<dark_gray>"));
        edge.setItemMeta(em);
        // fill the bottom row
        for (int i = 36; i < 45; i++) inv.setItem(i, edge);

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.1f);
    }

    private List<net.kyori.adventure.text.Component> lore(String... lines) {
        return List.of(lines).stream()
                .map(l -> mm(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p
                && e.getClickedInventory() == e.getView().getTopInventory()
                && e.getCurrentItem() != null
                && e.getCurrentItem().getType() != Material.WHITE_STAINED_GLASS_PANE) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.6f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }
}
