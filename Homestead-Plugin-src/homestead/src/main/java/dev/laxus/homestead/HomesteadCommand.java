package dev.laxus.homestead;

import dev.laxus.homestead.cooking.CookingModule;
import dev.laxus.homestead.decor.DecorModule;
import dev.laxus.homestead.quests.QuestModule;
import dev.laxus.homestead.survival.SurvivalModule;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.laxus.homestead.Text.mm;

public final class HomesteadCommand implements CommandExecutor, TabCompleter {
    private final HomesteadPlugin plugin;

    public HomesteadCommand(HomesteadPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("quests")) {
            return quests(sender, args);
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("homestead.admin")) return deny(sender);
                plugin.reload();
                sender.sendMessage(mm("<green>Homestead reloaded."));
            }
            case "season" -> {
                SurvivalModule s = plugin.survival();
                if (!(sender instanceof Player p) || s == null) {
                    sender.sendMessage(mm("<red>Survival module is disabled or you are not a player."));
                    return true;
                }
                int days = plugin.getConfig().getInt("seasons.days-per-season", 7);
                Season season = s.season(p.getWorld());
                sender.sendMessage(mm("<gray>It is <white>day " + Season.dayInSeason(p.getWorld(), days)
                        + " <gray>of " + season.display + "<gray>. Thirst: <aqua>"
                        + String.format("%.1f", s.thirst(p)) + "<gray>/20"));
            }
            case "recipes" -> {
                if (sender instanceof Player p && plugin.menus() != null) {
                    plugin.menus().openRecipes(p);
                    return true;
                }
                sender.sendMessage(mm("<gold>Cooking pot: <gray>cauldron on a lit campfire. Add items, stir with a stick, serve with a bowl."));
                CookingModule c = plugin.cooking();
                if (c != null) for (String line : c.describeRecipes()) sender.sendMessage(mm(" <yellow>• " + line));
                if (plugin.wine() != null) {
                    sender.sendMessage(mm("<light_purple>Wine: <gray>open a barrel, put must bottles inside, wait for them to turn into wine."));
                    for (String line : plugin.wine().describe()) sender.sendMessage(mm(" <yellow>• " + line));
                }
            }
            case "give" -> {
                if (!sender.hasPermission("homestead.admin")) return deny(sender);
                if (args.length < 3) {
                    sender.sendMessage(mm("<gray>Usage: /" + label + " give <player> <item> [amount]"));
                    sender.sendMessage(mm("<dark_gray>Every item: /" + label + " items"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(mm("<red>Unknown player '" + args[1] + "'."));
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                int amountIdx = 3;

                ItemStack it = resolveItem(id);
                // legacy syntax kept: give <player> backpack <skin> [amount]
                if (it == null && id.equals("backpack") && args.length > 3 && plugin.backpack() != null
                        && plugin.backpack().skinIds().contains(args[3].toLowerCase(Locale.ROOT))) {
                    it = plugin.backpack().createBackpack(args[3].toLowerCase(Locale.ROOT));
                    amountIdx = 4;
                }
                if (it == null) {
                    sender.sendMessage(mm("<red>Unknown item '" + args[2] + "'. See /" + label + " items."));
                    return true;
                }

                int amount = 1;
                if (args.length > amountIdx) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[amountIdx]));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(mm("<red>Amount must be a number."));
                        return true;
                    }
                }
                int given = 0;
                while (given < amount) {
                    ItemStack stack = it.clone();
                    stack.setAmount(Math.min(it.getMaxStackSize(), amount - given));
                    for (ItemStack left : target.getInventory().addItem(stack).values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), left);
                    }
                    given += stack.getAmount();
                }
                sender.sendMessage(mm("<green>Gave " + given + "x " + args[2] + " to " + target.getName() + "."));
            }
            case "items" -> {
                if (!sender.hasPermission("homestead.admin")) return deny(sender);
                sender.sendMessage(mm("<gold>Giveable ids — /" + label + " give <player> <id> [amount]"));
                group(sender, "<yellow>Furniture:", plugin.decor() == null ? List.of() : plugin.decor().ids());
                group(sender, "<yellow>Backpacks:", plugin.backpack() == null ? List.of()
                        : concat("backpack", plugin.backpack().skinIds().stream().map(s -> "backpack:" + s).toList()));
                group(sender, "<yellow>Artifacts:", plugin.artifacts() == null ? List.of() : List.of("golden_leaf"));
                group(sender, "<yellow>Meals:", plugin.cooking() == null ? List.of() : plugin.cooking().recipeIds());
                group(sender, "<yellow>Wines:", plugin.wine() == null ? List.of() : plugin.wine().ids());
            }
            case "setseason" -> {
                if (!sender.hasPermission("homestead.admin")) return deny(sender);
                Season target = null;
                if (args.length > 1) {
                    for (Season s : Season.values()) if (s.name().equalsIgnoreCase(args[1])) target = s;
                }
                if (target == null) {
                    sender.sendMessage(mm("<gray>Usage: /" + label + " setseason <spring|summer|autumn|winter>"));
                    return true;
                }
                int days = plugin.getConfig().getInt("seasons.days-per-season", 7);
                if (sender instanceof Player p) {
                    applySeason(p.getWorld(), target, days);
                    sender.sendMessage(mm("<green>" + p.getWorld().getName() + " is now " + target.display + "<green>."));
                } else {
                    for (World w : Bukkit.getWorlds()) {
                        if (plugin.worldAllowed(w)) applySeason(w, target, days);
                    }
                    sender.sendMessage(mm("<green>All worlds set to " + target.display + "<green>."));
                }
            }
            default -> {
                sender.sendMessage(mm("<gold>Homestead <gray>- /quests, /homestead season, /homestead recipes"));
                if (sender.hasPermission("homestead.admin")) {
                    sender.sendMessage(mm("<gray>Admin: /homestead reload, items, give <player> <id> [n], setseason <season>"));
                }
            }
        }
        return true;
    }

    /** Any giveable item: furniture, backpack[:skin], golden_leaf, meals, wines. */
    private ItemStack resolveItem(String id) {
        if (id.equals("golden_leaf") && plugin.artifacts() != null) return plugin.artifacts().createGoldenLeaf();
        if (plugin.backpack() != null && (id.equals("backpack") || id.startsWith("backpack:"))) {
            int i = id.indexOf(':');
            if (i < 0) return plugin.backpack().createBackpack();
            String skin = id.substring(i + 1);
            return plugin.backpack().skinIds().contains(skin) ? plugin.backpack().createBackpack(skin) : null;
        }
        ItemStack it = plugin.decor() == null ? null : plugin.decor().createItem(id);
        if (it == null && plugin.cooking() != null) it = plugin.cooking().createMeal(id);
        if (it == null && plugin.wine() != null) it = plugin.wine().createWine(id);
        return it;
    }

    private void group(CommandSender sender, String title, List<String> ids) {
        if (ids.isEmpty()) return;
        sender.sendMessage(title);
        for (int i = 0; i < ids.size(); i += 10) {
            sender.sendMessage(mm("<white>" + String.join("<gray>, <white>",
                    ids.subList(i, Math.min(ids.size(), i + 10)))));
        }
    }

    private List<String> concat(String first, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    /** Flips the world to the wanted season without changing the time of day. */
    private void applySeason(World w, Season target, int days) {
        long full = w.getFullTime();
        long day = full / 24000L;
        long tod = full % 24000L;
        long cycle = Math.max(1, days) * 4L;
        long cur = ((day % cycle) + cycle) % cycle;
        long wanted = target.ordinal() * (long) days;
        long delta = ((wanted - cur) % cycle + cycle) % cycle;
        if (delta != 0) w.setFullTime((day + delta) * 24000L + tod);
    }

    private boolean quests(CommandSender sender, String[] args) {
        QuestModule q = plugin.quests();
        if (!(sender instanceof Player p) || q == null) {
            sender.sendMessage(mm("<red>Quests are disabled or you are not a player."));
            return true;
        }
        boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        if (!all && plugin.menus() != null) {
            plugin.menus().openQuests(p);
            return true;
        }
        p.sendMessage(mm("<gold>--- Quests ---"));
        for (String line : q.lines(p, all)) p.sendMessage(mm(line));
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(mm("<red>You don't have permission."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("quests")) {
            if (args.length == 1) out.add("all");
            return out;
        }
        if (args.length == 1) {
            out.addAll(List.of("help", "season", "recipes"));
            if (sender.hasPermission("homestead.admin")) out.addAll(List.of("reload", "give", "items", "setseason"));
        } else if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            } else if (args.length == 3) {
                if (plugin.decor() != null) out.addAll(plugin.decor().ids());
                out.add("backpack");
                if (plugin.backpack() != null) {
                    for (String skin : plugin.backpack().skinIds()) out.add("backpack:" + skin);
                }
                out.add("golden_leaf");
                if (plugin.cooking() != null) out.addAll(plugin.cooking().recipeIds());
                if (plugin.wine() != null) out.addAll(plugin.wine().ids());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("setseason")) {
            for (Season s : Season.values()) out.add(s.name().toLowerCase(Locale.ROOT));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give") && args[2].equalsIgnoreCase("backpack")
                && plugin.backpack() != null) {
            out.addAll(plugin.backpack().skinIds());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
