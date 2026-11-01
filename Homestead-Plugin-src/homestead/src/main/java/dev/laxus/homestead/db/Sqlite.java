package dev.laxus.homestead.db;

import dev.laxus.homestead.HomesteadPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite storage for everything Homestead would otherwise keep in a file or lose on a restart.
 * Database: plugins/Homestead/data/homestead.db (Paper bundles the SQLite JDBC driver).
 * Holds quest progress and cooking pots. Furniture, wine barrels and backpack contents belong
 * to entities/items and persist without any extra storage.
 * Reads happen once at startup on the main thread; every write is queued onto a single
 * worker thread (main thread never waits on the database).
 */
public final class Sqlite {

    public record QuestRow(UUID uuid, String quest, int progress, boolean done) {}

    public record PotRow(UUID world, int x, int y, int z,
                         String contents, boolean cooking, long readyAt, String readyId, int servings) {}

    private final HomesteadPlugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Homestead-SQLite");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    public Sqlite(HomesteadPlugin plugin) {
        this.plugin = plugin;
        open();
    }

    private void open() {
        File dir = new File(plugin.getDataFolder(), "data");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "homestead.db").getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS quest_progress ("
                        + "uuid TEXT NOT NULL, quest TEXT NOT NULL,"
                        + "progress INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY (uuid, quest))");
                st.execute("CREATE TABLE IF NOT EXISTS cooking_pots ("
                        + "world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                        + "contents TEXT NOT NULL DEFAULT '', cooking INTEGER NOT NULL DEFAULT 0,"
                        + "ready_at INTEGER NOT NULL DEFAULT 0, ready_id TEXT NOT NULL DEFAULT '',"
                        + "servings INTEGER NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY (world, x, y, z))");
            }
            plugin.getLogger().info("SQLite storage ready at " + new File(dir, "homestead.db").getAbsolutePath());
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not open the SQLite database at " + dir, ex);
        }
        importLegacyQuestData();
    }

    /** One-time import of the old quest-data.yml into SQLite; the file is renamed afterwards. */
    private void importLegacyQuestData() {
        File legacy = new File(plugin.getDataFolder(), "quest-data.yml");
        if (!legacy.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(legacy);
        ConfigurationSection players = y.getConfigurationSection("players");
        int n = 0;
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                for (String quest : players.getStringList(key + ".done")) {
                    upsertQuest(id, quest, 0, true);
                    n++;
                }
                ConfigurationSection pr = players.getConfigurationSection(key + ".progress");
                if (pr != null) {
                    for (Map.Entry<String, Object> en : pr.getValues(false).entrySet()) {
                        if (en.getValue() instanceof Number num) {
                            upsertQuest(id, en.getKey(), num.intValue(), false);
                            n++;
                        }
                    }
                }
            }
        }
        try {
            Files.move(legacy.toPath(), Path.of(legacy.getPath() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not move quest-data.yml out of the way: " + ex.getMessage());
            return;
        }
        plugin.getLogger().info("Imported " + n + " quest entr(y/ies) from quest-data.yml into SQLite.");
    }

    // -------------------------------------------------------------- lifecycle

    /** Waits until every queued write has reached the database. */
    public void flush() {
        try {
            worker.submit(() -> {}).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
        }
    }

    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }

    private void runAsync(SqlTask task) {
        worker.execute(() -> {
            try {
                task.run();
            } catch (SQLException ex) {
                plugin.getLogger().warning("SQLite write failed: " + ex.getMessage());
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("SQLite write error: " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------ quest data

    public List<QuestRow> loadQuests() {
        List<QuestRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid, quest, progress, done FROM quest_progress");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new QuestRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4) != 0));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not read quest progress: " + ex.getMessage());
        }
        return rows;
    }

    public void upsertQuest(UUID uuid, String quest, int progress, boolean done) {
        runAsync(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quest_progress (uuid, quest, progress, done) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(uuid, quest) DO UPDATE SET progress = excluded.progress, done = excluded.done")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, quest);
                ps.setInt(3, progress);
                ps.setInt(4, done ? 1 : 0);
                ps.executeUpdate();
            }
        });
    }

    // ---------------------------------------------------------- cooking pots

    public List<PotRow> loadPots() {
        List<PotRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT world, x, y, z, contents, cooking, ready_at, ready_id, servings FROM cooking_pots");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new PotRow(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getInt(6) != 0, rs.getLong(7), rs.getString(8), rs.getInt(9)));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not read cooking pots: " + ex.getMessage());
        }
        return rows;
    }

    public void savePot(PotRow row) {
        runAsync(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cooking_pots (world, x, y, z, contents, cooking, ready_at, ready_id, servings) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET contents = excluded.contents, cooking = excluded.cooking, "
                            + "ready_at = excluded.ready_at, ready_id = excluded.ready_id, servings = excluded.servings")) {
                ps.setString(1, row.world().toString());
                ps.setInt(2, row.x());
                ps.setInt(3, row.y());
                ps.setInt(4, row.z());
                ps.setString(5, row.contents());
                ps.setInt(6, row.cooking() ? 1 : 0);
                ps.setLong(7, row.readyAt());
                ps.setString(8, row.readyId());
                ps.setInt(9, row.servings());
                ps.executeUpdate();
            }
        });
    }

    public void deletePot(PotRow row) {
        runAsync(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM cooking_pots WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, row.world().toString());
                ps.setInt(2, row.x());
                ps.setInt(3, row.y());
                ps.setInt(4, row.z());
                ps.executeUpdate();
            }
        });
    }
}
