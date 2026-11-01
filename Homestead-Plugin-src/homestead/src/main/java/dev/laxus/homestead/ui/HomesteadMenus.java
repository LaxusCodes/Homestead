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
 * Framed, colour-coded menus with progress bars. They are view-only: every click is cancelled,
 * so nothing can be taken out. (The old quest menu had a loop that never advanced when there
 * were fewer quests than slots; that hang is gone.)
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

    // ------------------------------------------------------------ shared pieces

    /** Blank pane: no name, no tooltip, so the frame never shows hover text. */
    private ItemStack filler(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(net.kyori.adventure.text.Component.space());
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    /** Frame the inventory (outer ring) with the given pane colours; corners use the accent. */
    private void frame(Inventory inv, Material pane, Material accent) {
        int rows = inv.getSize() / 9;
        for (int i = 0; i < inv.getSize(); i++) {
            int r = i / 9, c = i % 9;
            boolean edge = r == 0 || r == rows - 1 || c == 0 || c == 8;
            if (!edge) continue;
            boolean corner = (r == 0 || r == rows - 1) && (c == 0 || c == 8);
            inv.setItem(i, filler(corner ? accent : pane));
        }
    }

    /** Slots inside the frame, left to right, top to bottom (7 per row). */
    private List<Integer> innerSlots(int rows) {
        List<Integer> out = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c <= 7; c++) out.add(r * 9 + c);
        }
        return out;
    }

    /** 10-segment progress bar coloured by how far along it is. */
    private String bar(int have, int need) {
        double f = Math.max(0.0, Math.min(1.0, (double) have / Math.max(1, need)));
        int filled = (int) Math.round(f * 10);
        String col = f >= 1.0 ? "<green>" : f >= 0.5 ? "<yellow>" : "<gold>";
        return col + "\u25B0".repeat(filled) + "<dark_gray>" + "\u25B1".repeat(10 - filled)
                + " <white>" + (int) Math.round(f * 100) + "%";
    }

    // ----------------------------------------------------------------- quests

    public void openQuests(Player p) {
        List<String[]> items = plugin.quests() != null ? plugin.quests().menuItems(p) : List.of();
        int rows = Math.max(3, Math.min(6, (items.size() + 6) / 7 + 2));
        MenuHolder h = new MenuHolder("quests");
        Inventory inv = Bukkit.createInventory(h, rows * 9, mm("<gradient:#7fd36b:#2e8b57><bold>Homestead Quests"));
        h.inv = inv;
        frame(inv, Material.GREEN_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE);
        for (int i : innerSlots(rows)) inv.setItem(i, filler(Material.BLACK_STAINED_GLASS_PANE));

        List<Integer> slots = innerSlots(rows);
        int doneCount = 0, idx = 0;
        for (String[] q : items) {
            String name = q[1];
            String type = q[2];
            String desc = q[3];
            boolean done = q[4].equals("1");
            boolean open = q[5].equals("1");
            int have = Integer.parseInt(q[6]);
            int need = Integer.parseInt(q[7]);
            if (done) doneCount++;

            ItemStack icon = done ? new ItemStack(Material.LIME_DYE) : open ? iconFor(type) : new ItemStack(Material.GRAY_DYE);
            ItemMeta m = icon.getItemMeta();
            m.displayName(item(done ? "<green>\u2714 " + name : open ? "<yellow>" + name : "<dark_gray>" + name));
            List<String> lore = new ArrayList<>();
            if (done) {
                lore.add("<green>Completed");
            } else if (open) {
                lore.add("<gray>" + desc);
                lore.add("");
                lore.add(bar(have, need) + " <dark_gray>(" + have + "/" + need + ")");
            } else {
                lore.add("<dark_gray>Locked - finish the earlier quests first");
            }
            m.lore(lore.stream().map(ll -> mm(ll).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
            m.setHideTooltip(false);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            icon.setItemMeta(m);
            if (idx < slots.size()) inv.setItem(slots.get(idx++), icon);
        }

        // summary in the bottom-centre of the frame
        ItemStack sum = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta sm = sum.getItemMeta();
        sm.displayName(item("<gold>Your progress"));
        sm.lore(lore(bar(doneCount, Math.max(1, items.size())), "<gray>" + doneCount + " of " + items.size() + " quests completed"));
        sum.setItemMeta(sm);
        inv.setItem(inv.getSize() - 5, sum);

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
        Inventory inv = Bukkit.createInventory(h, 54, mm("<gradient:#e0a458:#a8431f><bold>Homestead Recipe Book"));
        h.inv = inv;
        frame(inv, Material.ORANGE_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE);
        for (int i : innerSlots(6)) inv.setItem(i, filler(Material.BLACK_STAINED_GLASS_PANE));

        // top three rows: everything you can cook (pot) - also craftable by hand, one serving
        List<Integer> slots = innerSlots(6);
        int idx = 0;
        CookingModule c = plugin.cooking();
        if (c != null) {
            for (String[] entry : c.menuEntries()) {
                ItemStack icon = c.createMeal(entry[0]);
                if (icon == null) icon = new ItemStack(Material.BOWL);
                ItemMeta m = icon.getItemMeta();
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore(entry[1]));
                lines.addAll(lore("", "<dark_gray>Pot: several servings  |  Crafting table: 1 serving"));
                m.lore(lines);
                icon.setItemMeta(m);
                if (idx < 21) inv.setItem(slots.get(idx++), icon);
            }
        }

        // bottom inner row (row 4): guides
        int row = 4 * 9;
        WineModule wine = plugin.wine();
        if (wine != null) {
            inv.setItem(row + 1, guide(Material.BARREL, "<gold>Barrel Fermentation",
                    "<gray>Cook a must in the pot, then open any barrel",
                    "<gray>and put the bottles inside. No right-click.",
                    "<dark_gray>The timer starts the moment they land in,",
                    "<dark_gray>counts down live and keeps running when closed."));
            inv.setItem(row + 2, guide(Material.HONEY_BOTTLE, "<dark_purple>Wines",
                    "<gray>Warmth and small boosts, but Nausea stacks",
                    "<gray>if you keep drinking.",
                    "<dark_gray>Shortcut: 3 must bottles in a crafting table."));
        }
        inv.setItem(row + 3, guide(Material.OAK_STAIRS, "<gold>Furniture",
                "<gray>Every piece is craftable at a crafting table.",
                "<dark_gray>Right-click a block to place, right-click to sit,",
                "<dark_gray>sneak + right-click to pick up."));
        inv.setItem(row + 4, guide(Material.LEATHER, "<gold>Backpack",
                "<gray>7x leather + string + chest (LSL / LCL / LLL)",
                "<dark_gray>Combine it with a dye to change its skin."));
        inv.setItem(row + 5, guide(Material.CAMPFIRE, "<gold>Cooking",
                "<gray>Cauldron on a lit campfire, add ingredients,",
                "<gray>stir with a stick, serve with a bowl."));
        inv.setItem(row + 6, guide(Material.PAPER, "<gray>Quests",
                "<gray>/quests - see your progress"));

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.1f);
    }

    private ItemStack guide(Material mat, String name, String... lines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(item(name));
        m.lore(lore(lines));
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
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
                && !e.getCurrentItem().getType().name().endsWith("_STAINED_GLASS_PANE")) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.6f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }
}
