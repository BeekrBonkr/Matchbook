package com.slg.matchbook.config;

import com.slg.matchbook.MatchbookPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads/saves Matchbook config from the MBedwars add-on data folder:
 *   plugins/MBedwars/add-ons/Matchbook/config.yml
 */
public final class MatchbookConfig {

    private final MatchbookPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    public MatchbookConfig(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getAddonDataFolder(), "config.yml");
    }

    public void load() {
        ensureDefaultAndUpdate();
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration raw() {
        if (yml == null) load();
        return yml;
    }

    public StorageType storageType() {
        String v = raw().getString("storage.type", "yaml");
        if (v == null) return StorageType.YAML;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("mysql") ? StorageType.MYSQL : StorageType.YAML;
    }

    public boolean debugLogging() {
        return raw().getBoolean("logging.debug", false);
    }

    /**
     * Hub/lobby mode: Matchbook skips match tracking entirely (no listeners, no sessions,
     * no writes) and only reads from the configured storage backend. Read at startup only;
     * changing it requires a restart, not just /mb reload.
     */
    public boolean hubMode() {
        return raw().getBoolean("mode.hub", false);
    }

    /**
     * Stat keys to snapshot from MBedwars. If empty, uses sensible defaults.
     */
    public List<String> trackedKeys() {
        List<String> keys = raw().getStringList("match.tracked_keys");
        if (keys == null) keys = List.of();

        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (k == null) continue;
            String t = k.trim();
            if (!t.isEmpty()) out.add(t);
        }

        return out.isEmpty() ? com.slg.matchbook.StatSnapshot.DEFAULT_TRACKED_KEYS : List.copyOf(out);
    }

    public RuntimeSettings runtimeSettings() {
        // Timing values (with bounds)
        int runningWaitTicksMax = clampInt(raw().getInt("match.running_wait_ticks_max", 100), 1, 20 * 60);
        long startSnapshotDelayTicks = clampLong(raw().getLong("match.start_snapshot_delay_ticks", 20L), 0L, 20L * 60L);
        long endSnapshotDelayTicks = clampLong(raw().getLong("match.end_snapshot_delay_ticks", 80L), 0L, 20L * 60L);
        long snapshotTimeoutTicks = clampLong(raw().getLong("match.snapshot_timeout_ticks", 80L), 1L, 20L * 60L);
        long joinClassifyDelayTicks = clampLong(raw().getLong("match.join_classify_delay_ticks", 60L), 0L, 20L * 60L);

        List<String> columns = raw().getStringList("export.columns");
        if (columns != null) {
            List<String> cleaned = new ArrayList<>();
            for (String c : columns) {
                if (c == null) continue;
                String t = c.trim();
                if (!t.isEmpty()) cleaned.add(t);
            }
            columns = cleaned;
        }

        return new RuntimeSettings(
                trackedKeys(),
                runningWaitTicksMax,
                startSnapshotDelayTicks,
                endSnapshotDelayTicks,
                snapshotTimeoutTicks,
                joinClassifyDelayTicks,
                new RuntimeSettings.ExportSettings(columns == null || columns.isEmpty() ? null : List.copyOf(columns))
        );
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private void ensureDefaultAndUpdate() {
        // Create default config if missing
        if (!file.exists()) {
            ensureDefault();
            return;
        }

        // If it exists, sync it against the packaged template every time we load (not just on a
        // version bump): fills in any missing keys and restores template comments while keeping
        // the user's own values.
        ConfigUpdater.updateIfNeeded(plugin, file, "config-version");
    }

    private void ensureDefault() {
        if (file.exists()) return;
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Matchbook: Failed to create config folder: " + file.getParentFile().getAbsolutePath());
            return;
        }

        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                // Extremely defensive fallback
                Files.writeString(file.toPath(), "storage:\n  type: yaml\nmysql:\n  host: localhost\n  port: 3306\n  database: matchbook\n  username: root\n  password: ''\n  useSSL: false\n  pool:\n    maximumPoolSize: 10\n    minimumIdle: 2\n    connectionTimeoutMs: 10000\n", java.nio.charset.StandardCharsets.UTF_8);
                return;
            }
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Matchbook: Failed to write default config.yml: " + e.getMessage());
        }
    }
}
