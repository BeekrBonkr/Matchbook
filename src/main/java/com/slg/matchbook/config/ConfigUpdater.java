package com.slg.matchbook.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public final class ConfigUpdater {

    private ConfigUpdater() {}

    /**
     * Merges the on-disk config with the packaged config.yml if config-version differs.
     *
     * - Keeps existing user values
     * - Adds missing keys from defaults
     * - Preserves extra user keys not in defaults
     * - Updates config-version to default version
     * - Creates a timestamped backup before writing
     */
    public static void updateIfNeeded(JavaPlugin plugin, File configFile, String versionKey) {
        YamlConfiguration defaults = loadDefaults(plugin, "config.yml");
        if (defaults == null) {
            plugin.getLogger().warning("Matchbook: Could not load packaged config.yml; skipping config update.");
            return;
        }

        if (!configFile.exists()) {
            // No config yet: just write defaults
            writeDefaults(plugin, configFile);
            return;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);

        String defaultVer = defaults.getString(versionKey, "0.0.0");
        String currentVer = current.getString(versionKey, "0.0.0");

        if (defaultVer == null) defaultVer = "0.0.0";
        if (currentVer == null) currentVer = "0.0.0";

        if (defaultVer.equals(currentVer)) {
            return; // up to date
        }

        plugin.getLogger().info("Matchbook: Detected config version change: " + currentVer + " -> " + defaultVer);
        backupConfig(plugin, configFile, currentVer);

        YamlConfiguration merged = new YamlConfiguration();

        // 1) Walk defaults first to keep stable ordering similar to packaged config
        mergeSection(defaults, current, merged, "");

        // 2) Add any extra keys user had that aren't in defaults
        Set<String> currentKeys = current.getKeys(true);
        for (String key : currentKeys) {
            if (current.isConfigurationSection(key)) continue;
            if (!defaults.contains(key)) {
                merged.set(key, current.get(key));
            }
        }

        // Ensure version is updated
        merged.set(versionKey, defaultVer);

        try {
            merged.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Matchbook: Failed to save merged config.yml: " + e.getMessage());
        }
    }

    private static void mergeSection(YamlConfiguration defaults, YamlConfiguration current, YamlConfiguration out, String path) {
        ConfigurationSection defSec = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (defSec == null) return;

        for (String key : defSec.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;

            if (defaults.isConfigurationSection(full)) {
                // Ensure section exists in output, then recurse
                if (out.getConfigurationSection(full) == null) out.createSection(full);
                mergeSection(defaults, current, out, full);
                continue;
            }

            if (current.contains(full)) {
                out.set(full, current.get(full));
            } else {
                out.set(full, defaults.get(full));
            }
        }
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String resourceName) {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) return null;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeDefaults(JavaPlugin plugin, File configFile) {
        if (!configFile.getParentFile().exists() && !configFile.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Matchbook: Failed to create config folder: " + configFile.getParentFile().getAbsolutePath());
            return;
        }

        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            Files.copy(in, configFile.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Matchbook: Failed to write default config.yml: " + e.getMessage());
        }
    }

    private static void backupConfig(JavaPlugin plugin, File configFile, String currentVer) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(configFile.getParentFile(),
                "config.yml.bak-" + currentVer + "-" + ts);

        try {
            Files.copy(configFile.toPath(), backup.toPath());
            plugin.getLogger().info("Matchbook: Backed up old config to " + backup.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Matchbook: Failed to backup old config.yml: " + e.getMessage());
        }
    }
}
