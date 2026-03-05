package com.slg.matchbook.storage;

import com.slg.matchbook.*;
import com.slg.matchbook.io.MatchYamlCodec;
import com.slg.matchbook.model.MatchDocument;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class YamlMatchRepository implements MatchRepository {

    private final MatchbookPlugin plugin;
    private final MatchStorage storage;

    public YamlMatchRepository(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.storage = new MatchStorage(plugin);
    }

    @Override public void init() {}

    @Override public void shutdown() {}

    @Override
    public HealthCheckResult healthCheck() {
        try {
            File root = plugin.getAddonDataFolder();
            if (!root.exists() && !root.mkdirs()) {
                return HealthCheckResult.fail("YAML storage: could not create data folder: " + root.getAbsolutePath());
            }

            File matchesDir = new File(root, "matches");
            if (!matchesDir.exists() && !matchesDir.mkdirs()) {
                return HealthCheckResult.fail("YAML storage: could not create matches folder: " + matchesDir.getAbsolutePath());
            }
            if (!matchesDir.canWrite()) {
                return HealthCheckResult.fail("YAML storage: matches folder is not writable: " + matchesDir.getAbsolutePath());
            }
            return HealthCheckResult.ok("YAML storage: OK (" + matchesDir.getAbsolutePath() + ")");
        } catch (Exception e) {
            return HealthCheckResult.fail("YAML storage: " + e.getMessage());
        }
    }

    @Override
    public void saveMatch(MatchDocument doc) throws IOException {
        storage.saveMatchYaml(doc);
    }

    @Override
    public File findMatchFileById(String matchId) {
        if (matchId == null || matchId.isBlank()) return null;

        // Fast path: use user index if it contains a mapping (matchId -> relative path)
        File matchesDir = new File(plugin.getAddonDataFolder(), "matches");
        if (!matchesDir.exists()) return null;

        // Slow scan: traverse day folders and read match.match_id from each yaml
        File[] dayDirs = matchesDir.listFiles(File::isDirectory);
        if (dayDirs == null) return null;

        for (File day : dayDirs) {
            File[] files = day.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                    String id = yml.getString("match.match_id", "");
                    if (matchId.equalsIgnoreCase(id)) return f;
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    @Override
    public YamlConfiguration loadMatchYaml(String matchId) {
        File f = findMatchFileById(matchId);
        if (f == null) return null;
        return YamlConfiguration.loadConfiguration(f);
    }

    @Override
    public List<String> listMatchIdsForPlayer(UUID playerUuid) {
        UserMatchIndex idx = new UserMatchIndex(plugin);
        return idx.getMatchIdsForPlayer(playerUuid);
    }


    @Override
    public List<String> listAllMatchIds() {
        File matchesDir = new File(plugin.getAddonDataFolder(), "matches");
        if (!matchesDir.exists()) return List.of();

        class Entry {
            final String id;
            final long start;
            Entry(String id, long start) { this.id = id; this.start = start; }
        }

        List<Entry> entries = new ArrayList<>();

        File[] dayDirs = matchesDir.listFiles(File::isDirectory);
        if (dayDirs == null) return List.of();

        for (File day : dayDirs) {
            File[] files = day.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                    String id = yml.getString("match.match_id", "");
                    if (id == null || id.isBlank()) continue;
                    long start = yml.getLong("match.start_unix", 0L);
                    entries.add(new Entry(id, start));
                } catch (Exception ignored) {
                }
            }
        }

        entries.sort((a, b) -> Long.compare(b.start, a.start));
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.id);
        return out;
    }

}
