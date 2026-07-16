package com.slg.matchbook.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

public final class ConfigUpdater {

    private ConfigUpdater() {}

    /**
     * Syncs the on-disk config against the packaged template on every startup — not just when
     * config-version changes.
     *
     * - Keeps existing user values
     * - Adds missing keys from defaults
     * - Always restores comments (block + inline) from the packaged template, so wording fixes/new
     *   explanations reach existing installs even without a config-version bump, and so a config
     *   that's had its comments stripped by an older Matchbook version gets them back
     * - Preserves extra keys (and their own comments) the user added that aren't in the template
     * - Updates config-version to the default version
     * - Only touches disk (and only backs up) when something actually needed fixing
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
        boolean versionChanged = !defaultVer.equals(currentVer);

        YamlConfiguration merged = new YamlConfiguration();

        // The big top-of-file banner is a file-level "header", not a comment attached to the first
        // key — Paper's per-node getComments()/setComments() don't cover it, so it needs its own copy.
        List<String> templateHeader = defaults.options().getHeader();
        merged.options().setHeader(templateHeader);
        boolean headerChanged = !templateHeader.equals(current.options().getHeader());

        // 1) Walk defaults first to keep stable ordering similar to packaged config. Reports true
        // if any key or comment had to be filled in / restored from the template.
        boolean driftFound = mergeSection(defaults, current, merged, "") || headerChanged;

        // 2) Add any extra keys user had that aren't in defaults, keeping their own comments too.
        Set<String> currentKeys = current.getKeys(true);
        for (String key : currentKeys) {
            if (current.isConfigurationSection(key)) continue;
            if (!defaults.contains(key)) {
                merged.set(key, current.get(key));
                merged.setComments(key, current.getComments(key));
                merged.setInlineComments(key, current.getInlineComments(key));
            }
        }

        // Ensure version is updated
        merged.set(versionKey, defaultVer);

        if (!driftFound && !versionChanged) {
            return; // already has every key, every template comment, and the right version
        }

        if (versionChanged) {
            plugin.getLogger().info("Matchbook: Detected config version change: " + currentVer + " -> " + defaultVer);
        } else {
            plugin.getLogger().info("Matchbook: Restoring missing config keys/comments (config-version unchanged: " + currentVer + ")");
        }
        backupConfig(plugin, configFile, currentVer);

        try {
            merged.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Matchbook: Failed to save merged config.yml: " + e.getMessage());
        }
    }

    /** @return true if any key was missing (filled from defaults) or any comment differed from the template. */
    private static boolean mergeSection(YamlConfiguration defaults, YamlConfiguration current, YamlConfiguration out, String path) {
        ConfigurationSection defSec = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (defSec == null) return false;

        boolean changed = false;

        for (String key : defSec.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;

            if (defaults.isConfigurationSection(full)) {
                // Ensure section exists in output, then recurse
                if (out.getConfigurationSection(full) == null) out.createSection(full);
                if (restoreTemplateComments(defaults, current, out, full)) changed = true;
                if (mergeSection(defaults, current, out, full)) changed = true;
                continue;
            }

            if (current.contains(full)) {
                out.set(full, current.get(full));
            } else {
                out.set(full, defaults.get(full));
                changed = true;
            }

            if (restoreTemplateComments(defaults, current, out, full)) changed = true;
        }

        return changed;
    }

    /**
     * Copies the template's comments onto {@code out} at {@code path} (comments ALWAYS come from
     * the packaged template, never the user's file), and reports whether the user's file actually
     * differed from the template there — used only to decide whether a rewrite is worth doing.
     */
    private static boolean restoreTemplateComments(YamlConfiguration defaults, YamlConfiguration current,
                                                     YamlConfiguration out, String path) {
        List<String> templateComments = defaults.getComments(path);
        List<String> templateInline = defaults.getInlineComments(path);

        out.setComments(path, templateComments);
        out.setInlineComments(path, templateInline);

        return !templateComments.equals(current.getComments(path)) || !templateInline.equals(current.getInlineComments(path));
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
