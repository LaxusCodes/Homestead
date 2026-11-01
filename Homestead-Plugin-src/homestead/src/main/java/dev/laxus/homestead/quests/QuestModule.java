package dev.laxus.homestead.quests;

import dev.laxus.homestead.AbstractModule;
import dev.laxus.homestead.HomesteadPlugin;
import dev.laxus.homestead.db.Sqlite;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.laxus.homestead.Text.mm;

/**
 * Quest chains defined in quests.yml.
 * Types: BREAK, HARVEST, KILL, CRAFT, COOK, EAT, DRINK, DECOR, BREW, FIND.
 * Target matching: "*" = anything, exact name (case-insensitive), or a suffix such as LOG matching OAK_LOG.
 */
public final class QuestModule extends AbstractModule {

    public record Quest(String id, String name, String description, String type, String target, int amount,
                        List<String> requires, List<String> items, List<String> commands, int xp) {}

    private static final class PlayerData {
        final Set<String> done = new LinkedHashSet<>();
        final Map<String, Integer> progress = new HashMap<>();
    }

    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();

    public QuestModule(HomesteadPlugin plugin) {
        super(plugin);
    }

    @Override
    protected void onEnable() {
        loadQuests();
        loadData();
    }

    @Override
    protected void onDisable() {
        quests.clear();
        data.clear();
    }

    // ---------------------------------------------------------------- load

    private void loadQuests() {
        File f = new File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = y.getConfigurationSection("quests");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection q = sec.getConfigurationSection(id);
            if (q == null) continue;
            quests.put(id, new Quest(id, q.getString("name", id), q.getString("description", ""),
                    q.getString("type", "BREAK"), q.getString("target", "*"), Math.max(1, q.getInt("amount", 1)),
                    q.getStringList("requires"), q.getStringList("rewards.items"),
                    q.getStringList("rewards.commands"), q.getInt("rewards.xp", 0)));
        }
        plugin.getLogger().info("Loaded " + quests.size() + " quest(s).");
    }

    /** Player progress lives in SQLite (legacy quest-data.yml files are imported there automatically). */
    private void loadData() {
        for (Sqlite.QuestRow row : plugin.db().loadQuests()) {
            PlayerData d = data.computeIfAbsent(row.uuid(), k -> new PlayerData());
            if (row.done()) d.done.add(row.quest());
            else if (row.progress() > 0) d.progress.put(row.quest(), row.progress());
        }
        plugin.getLogger().info("Loaded quest progress for " + data.size() + " player(s).");
    }

    private PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    private boolean unlocked(PlayerData d, Quest q) {
        return d.done.containsAll(q.requires());
    }

    // ------------------------------------------------------------ progress

    public void progress(Player p, String type, String target, int amount) {
        PlayerData d = data(p);
        for (Quest q : new ArrayList<>(quests.values())) {
            if (!q.type().equalsIgnoreCase(type)) continue;
            if (d.done.contains(q.id()) || !unlocked(d, q)) continue;
            if (!matches(q.target(), target)) continue;

            int now = d.progress.merge(q.id(), amount, Integer::sum);
            plugin.db().upsertQuest(p.getUniqueId(), q.id(), now, false);
            if (now >= q.amount()) {
                complete(p, d, q);
            } else {
                p.sendActionBar(mm("<gold>" + q.name() + " <gray>" + now + "/" + q.amount()));
            }
        }
    }

    private boolean matches(String questTarget, String actual) {
        if (questTarget.equals("*")) return true;
        if (questTarget.equalsIgnoreCase(actual)) return true;
        return actual.toUpperCase().endsWith("_" + questTarget.toUpperCase());
    }

    private void complete(Player p, PlayerData d, Quest q) {
        d.done.add(q.id());
        d.progress.remove(q.id());
        plugin.db().upsertQuest(p.getUniqueId(), q.id(), 0, true);

        p.sendMessage(mm("<gold>★ Quest complete: <yellow>" + q.name()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        for (String s : q.items()) {
            ItemStack it = parseReward(s);
            if (it == null) {
                plugin.getLogger().warning("Quest '" + q.id() + "': bad reward item '" + s + "'");
                continue;
            }
            for (ItemStack left : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        if (q.xp() > 0) p.giveExp(q.xp());
        for (String cmd : q.commands()) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    cmd.replace("{player}", p.getName()));
        }

        for (Quest other : quests.values()) {
            if (!d.done.contains(other.id()) && other.requires().contains(q.id()) && unlocked(d, other)) {
                p.sendMessage(mm("<green>New quest unlocked: <white>" + other.name()));
            }
        }
    }

    /** "MATERIAL" / "MATERIAL:amount" / "meal:recipe_id[:amount]" */
    private ItemStack parseReward(String s) {
        String[] parts = s.split(":");
        try {
            if (parts[0].equalsIgnoreCase("meal") && parts.length >= 2) {
                if (plugin.cooking() == null) return null;
                ItemStack meal = plugin.cooking().createMeal(parts[1]);
                if (meal != null && parts.length > 2) meal.setAmount(Integer.parseInt(parts[2]));
                return meal;
            }
            Material m = Material.matchMaterial(parts[0]);
            if (m == null) return null;
            return new ItemStack(m, parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------- display

    public List<String> lines(Player p, boolean all) {
        PlayerData d = data(p);
        List<String> out = new ArrayList<>();
        for (Quest q : quests.values()) {
            boolean done = d.done.contains(q.id());
            boolean open = unlocked(d, q);
            if (done) {
                if (all) out.add("<green>✔ <gray>" + q.name());
            } else if (open) {
                int have = d.progress.getOrDefault(q.id(), 0);
                out.add("<gold>➤ <yellow>" + q.name() + " <gray>" + bar(have, q.amount()) + " " + have + "/" + q.amount());
                out.add("   <dark_gray>" + q.description());
            } else if (all) {
                out.add("<dark_gray>✖ " + q.name() + " (locked)");
            }
        }
        if (out.isEmpty()) out.add("<gray>No active quests. You're all caught up!");
        return out;
    }

    private String bar(int have, int need) {
        int filled = Math.min(10, (int) Math.round(10.0 * have / need));
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(10 - filled);
    }

    /**
     * Quest list for the GUI menu. Each row:
     * [id, name, type, description, done(0/1), unlocked(0/1), progress, amount]
     */
    public List<String[]> menuItems(Player p) {
        PlayerData d = data(p);
        List<String[]> out = new ArrayList<>();
        for (Quest q : quests.values()) {
            boolean done = d.done.contains(q.id());
            boolean open = unlocked(d, q);
            int have = d.progress.getOrDefault(q.id(), 0);
            out.add(new String[]{q.id(), q.name(), q.type(), q.description(),
                    done ? "1" : "0", open ? "1" : "0",
                    String.valueOf(have), String.valueOf(q.amount())});
        }
        return out;
    }

    // -------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        String name = b.getType().name();
        BlockData bd = b.getBlockData();
        if (bd instanceof Ageable a && a.getAge() == a.getMaximumAge()) {
            progress(p, "HARVEST", name, 1);
        }
        progress(p, "BREAK", name, 1);
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) progress(killer, "KILL", e.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        progress(p, "CRAFT", result.getType().name(), result.getAmount());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = data(e.getPlayer());
        if (d.done.isEmpty() && d.progress.isEmpty()) {
            e.getPlayer().sendMessage(mm("<gold>Welcome to the homestead! <gray>Type <yellow>/quests <gray>to get started."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // progress is written through to SQLite as it changes, nothing to flush on quit
    }
}
