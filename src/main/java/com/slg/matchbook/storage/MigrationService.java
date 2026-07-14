package com.slg.matchbook.storage;

import com.slg.matchbook.MatchStorage;
import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.UserMatchIndex;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * One-time admin migration helpers.
 *
 * - YAML -> MySQL: reads every match YAML file from addonDataFolder/matches and upserts into MySQL.
 * - MySQL -> YAML: reads every match row from MySQL and writes YAML files into addonDataFolder/matches.
 */
public final class MigrationService {

    private MigrationService() {}

    public static int migrateYamlToMySql(MatchbookPlugin plugin, boolean dryRun) throws Exception {
        plugin.getLogger().warning("Matchbook migration: YAML -> MySQL" + (dryRun ? " (dry-run)" : ""));

        // We create a separate MySQL repo using current config (even if plugin is running in YAML mode).
        // Never run schema DDL during a dry-run: it must not mutate the database while only previewing.
        MySqlMatchRepository mysql = new MySqlMatchRepository(plugin, plugin.getMatchbookConfig().raw());
        mysql.init(!dryRun);

        int imported = 0;
        int failed = 0;
        try {
            for (File f : scanAllYamlMatchFiles(plugin)) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String matchId = yml.getString("match.match_id", "");
                if (matchId == null || matchId.isBlank()) continue;

                try {
                    String yamlText = Files.readString(f.toPath(), StandardCharsets.UTF_8);

                    if (!dryRun) {
                        mysql.importMatchFromYaml(yml, yamlText);
                    }
                    imported++;
                } catch (Exception e) {
                    // One malformed/oversized row must not abort the rest of the batch.
                    failed++;
                    plugin.getLogger().warning("Matchbook migration: failed to import " + f.getName() + " (match " + matchId + "): " + e.getMessage());
                }
            }
        } finally {
            mysql.shutdown();
        }

        if (failed > 0) {
            plugin.getLogger().warning("Matchbook migration: YAML -> MySQL finished with " + failed + " failure(s); see warnings above.");
        }
        return imported;
    }

    public static int migrateMySqlToYaml(MatchbookPlugin plugin, boolean dryRun) throws Exception {
        plugin.getLogger().warning("Matchbook migration: MySQL -> YAML" + (dryRun ? " (dry-run)" : ""));

        MySqlMatchRepository mysql = new MySqlMatchRepository(plugin, plugin.getMatchbookConfig().raw());
        mysql.init(!dryRun);

        MatchStorage storage = new MatchStorage(plugin);
        UserMatchIndex index = new UserMatchIndex(plugin);

        int exported = 0;
        int failed = 0;
        try {
            List<String> ids = mysql.listAllMatchIds();
            for (String matchId : ids) {
                try {
                    YamlConfiguration yml = mysql.loadMatchYaml(matchId);
                    if (yml == null) continue;

                    long startUnix = yml.getLong("match.start_unix", 0L);
                    String arena = yml.getString("match.arena", "");
                    if (startUnix <= 0L || arena == null) continue;

                    String md5 = MatchStorage.md5Hex(arena);
                    File dayFolder = storage.getDayFolder(new Date(startUnix * 1000L));
                    File out = safeOutFile(dayFolder, startUnix, md5, matchId);

                    if (!dryRun) {
                        yml.save(out);

                        // Update user indexes (best-effort)
                        String relative = out.getParentFile().getName() + "/" + out.getName();
                        List<String> participants = yml.getStringList("match.participants");
                        for (String uuidStr : participants) {
                            try {
                                UUID u = UUID.fromString(uuidStr);
                                index.addMatchForPlayer(u, matchId, relative);
                            } catch (Exception ignored) {}
                        }
                    }

                    exported++;
                } catch (Exception e) {
                    // One malformed row must not abort the rest of the batch.
                    failed++;
                    plugin.getLogger().warning("Matchbook migration: failed to export match " + matchId + ": " + e.getMessage());
                }
            }
        } finally {
            mysql.shutdown();
        }

        if (failed > 0) {
            plugin.getLogger().warning("Matchbook migration: MySQL -> YAML finished with " + failed + " failure(s); see warnings above.");
        }
        return exported;
    }

    private static List<File> scanAllYamlMatchFiles(MatchbookPlugin plugin) {
        File matchesDir = new File(plugin.getAddonDataFolder(), "matches");
        if (!matchesDir.exists() || !matchesDir.isDirectory()) return List.of();

        List<File> out = new ArrayList<>();
        File[] dayDirs = matchesDir.listFiles(File::isDirectory);
        if (dayDirs == null) return List.of();

        for (File day : dayDirs) {
            File[] files = day.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            out.addAll(Arrays.asList(files));
        }
        // deterministic order
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    /**
     * Avoid overwriting an existing YAML file if it belongs to a different match_id.
     * This can happen if arenas share names, clocks collide, or an admin already exported once.
     */
    private static File safeOutFile(File dayFolder, long startUnix, String arenaMd5, String matchId) {
        File preferred = new File(dayFolder, startUnix + "-" + arenaMd5 + ".yml");
        if (!preferred.exists()) return preferred;

        try {
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(preferred);
            String existingId = existing.getString("match.match_id", "");
            if (existingId != null && existingId.equalsIgnoreCase(matchId)) return preferred;
        } catch (Throwable ignored) {
        }

        // Fallback: incorporate matchId to ensure uniqueness, but keep it human-scannable.
        // Sanitize: matchId ultimately comes from a MySQL row, which could be from a shared/tampered
        // DB — never let it inject path separators into the target file path.
        String safeId = matchId == null ? "" : matchId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dayFolder, startUnix + "-" + arenaMd5 + "-" + safeId + ".yml");
    }
}
