package com.slg.matchbook.config;

import com.slg.matchbook.MatchbookPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    private void ensureDefaultAndUpdate() {
        // Create default config if missing
        if (!file.exists()) {
            ensureDefault();
            return;
        }

        // If it exists, update/merge if version differs
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
