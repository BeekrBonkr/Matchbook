package com.slg.matchbook;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class UserMatchIndex {

    private final MatchbookPlugin plugin;

    public UserMatchIndex(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    private File usersDir() {
        File dir = new File(plugin.getAddonDataFolder(), "users");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Add a match reference for a player.
     *
     * New format:
     *  - matches: [ <matchId>, ... ]
     *  - paths:
     *      <matchId>: "MM-dd-yyyy/<filename>.yml"
     *
     * Backwards compatible with older installs where "matches" stored file paths.
     */
    public void addMatchForPlayer(UUID uuid, String matchId, String relativeMatchPath) {
        File f = new File(usersDir(), uuid.toString() + ".yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);

        List<String> matches = new ArrayList<>(yml.getStringList("matches"));

        // Back-compat: if the list contains file paths, leave them; but for new entries we store match IDs.
        // Remove duplicates of the matchId if present.
        matches.removeIf(s -> s != null && s.equalsIgnoreCase(matchId));
        matches.add(0, matchId);

        // Store mapping for fast lookup
        if (relativeMatchPath != null && !relativeMatchPath.isBlank()) {
            yml.set("paths." + matchId, relativeMatchPath);
        }

        // Optional cap so files don’t grow forever
        int cap = 500;
        if (matches.size() > cap) {
            matches = matches.subList(0, cap);
        }

        yml.set("matches", matches);

        try {
            yml.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Matchbook: failed updating user index " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Legacy helper for older call sites that only had a relative path.
     * Prefer {@link #addMatchForPlayer(UUID, String, String)}.
     */
    public void addMatchForPlayer(UUID uuid, String legacyRelativeMatchPath) {
        // Keep old behavior (store path) for backward compatibility
        File f = new File(usersDir(), uuid.toString() + ".yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);

        List<String> matches = new ArrayList<>(yml.getStringList("matches"));
        matches.removeIf(s -> s != null && s.equalsIgnoreCase(legacyRelativeMatchPath));
        matches.add(0, legacyRelativeMatchPath);

        int cap = 500;
        if (matches.size() > cap) {
            matches = matches.subList(0, cap);
        }
        yml.set("matches", matches);

        try {
            yml.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Matchbook: failed updating user index " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public List<String> getMatchIdsForPlayer(UUID uuid) {
        File f = new File(usersDir(), uuid.toString() + ".yml");
        if (!f.exists()) return List.of();

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        return new ArrayList<>(yml.getStringList("matches"));
    }
}
