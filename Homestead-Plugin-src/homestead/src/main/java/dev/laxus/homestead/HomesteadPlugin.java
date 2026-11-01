package dev.laxus.homestead;

import dev.laxus.homestead.artifacts.ArtifactModule;
import dev.laxus.homestead.backpack.BackpackModule;
import dev.laxus.homestead.cooking.CookingModule;
import dev.laxus.homestead.crafting.CraftingModule;
import dev.laxus.homestead.db.Sqlite;
import dev.laxus.homestead.decor.DecorModule;
import dev.laxus.homestead.farming.FarmingModule;
import dev.laxus.homestead.quests.QuestModule;
import dev.laxus.homestead.survival.SurvivalModule;
import dev.laxus.homestead.ui.HomesteadMenus;
import dev.laxus.homestead.wine.WineModule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class HomesteadPlugin extends JavaPlugin {
    private final List<AbstractModule> modules = new ArrayList<>();
    private SurvivalModule survival;
    private FarmingModule farming;
    private CookingModule cooking;
    private QuestModule quests;
    private DecorModule decor;
    private WineModule wine;
    private BackpackModule backpack;
    private ArtifactModule artifacts;
    private CraftingModule crafting;
    private HomesteadMenus menus;
    private Sqlite db;

    /** SQLite store (quest progress, cooking pots). All data files move here. */
    public Sqlite db() {
        return db;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "quests.yml").exists()) {
            saveResource("quests.yml", false);
        }
        db = new Sqlite(this);
        startModules();

        HomesteadCommand cmd = new HomesteadCommand(this);
        for (String name : List.of("homestead", "quests")) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(cmd);
                pc.setTabCompleter(cmd);
            }
        }
        getLogger().info("Homestead enabled with " + modules.size() + " module(s).");
    }

    @Override
    public void onDisable() {
        stopModules();
        if (db != null) {
            db.close();   // waits for the queued module shutdown writes, then frees the file
            db = null;
        }
    }

    public void reload() {
        stopModules();
        if (db != null) db.flush();   // queued module shutdown writes must land before the re-read
        reloadConfig();
        startModules();
    }

    private boolean moduleOn(String name) {
        return getConfig().getBoolean("modules." + name, true);
    }

    private void startModules() {
        survival = moduleOn("survival") ? new SurvivalModule(this) : null;
        farming = moduleOn("farming") ? new FarmingModule(this) : null;
        cooking = moduleOn("cooking") ? new CookingModule(this) : null;
        quests = moduleOn("quests") ? new QuestModule(this) : null;
        decor = moduleOn("decor") ? new DecorModule(this) : null;
        wine = moduleOn("wine") ? new WineModule(this) : null;
        backpack = moduleOn("backpack") ? new BackpackModule(this) : null;
        artifacts = moduleOn("artifacts") ? new ArtifactModule(this) : null;
        crafting = moduleOn("crafting") ? new CraftingModule(this) : null;
        menus = new HomesteadMenus(this);   // always on: /quests + /homestead recipes GUIs

        for (AbstractModule m : new AbstractModule[]{survival, farming, cooking, quests, decor, wine,
                backpack, artifacts, crafting, menus}) {
            if (m == null) continue;
            modules.add(m);
            try {
                m.enable();
            } catch (Throwable t) {
                // one broken module must not stop the whole plugin from loading
                getLogger().log(Level.SEVERE, "Module " + m.getClass().getSimpleName() + " failed to start", t);
            }
        }
    }

    private void stopModules() {
        for (AbstractModule m : modules) {
            try {
                m.disable();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Module " + m.getClass().getSimpleName() + " failed to stop cleanly", t);
            }
        }
        modules.clear();
        survival = null;
        farming = null;
        cooking = null;
        quests = null;
        decor = null;
        wine = null;
        backpack = null;
        artifacts = null;
        crafting = null;
        menus = null;
    }

    /** True for any item made by this plugin (furniture, meals, wine, backpacks, the Golden Leaf). */
    public boolean isHomesteadItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        String ns = getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey k : it.getItemMeta().getPersistentDataContainer().getKeys()) {
            if (k.getNamespace().equals(ns)) return true;
        }
        return false;
    }

    public boolean worldAllowed(World w) {
        List<String> list = getConfig().getStringList("enabled-worlds");
        return list.isEmpty() || list.contains(w.getName());
    }

    /** Report progress to the quest module (no-op if quests are disabled). */
    public void quest(Player p, String type, String target) {
        if (quests != null) quests.progress(p, type, target, 1);
    }

    public SurvivalModule survival() { return survival; }
    public FarmingModule farming() { return farming; }
    public CookingModule cooking() { return cooking; }
    public QuestModule quests() { return quests; }
    public DecorModule decor() { return decor; }
    public WineModule wine() { return wine; }
    public BackpackModule backpack() { return backpack; }
    public ArtifactModule artifacts() { return artifacts; }
    public CraftingModule crafting() { return crafting; }
    public HomesteadMenus menus() { return menus; }
}
