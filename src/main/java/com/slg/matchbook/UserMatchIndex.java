package com.slg.matchbook;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class UserMatchIndex {

    /**
     * Per-player file locks. Index updates run on async save threads, and two matches finishing
     * near-simultaneously with a shared player would otherwise do an unsynchronized
     * read-modify-write on the same users/&lt;uuid&gt;.yml — losing one match from that player's
     * history. Static because UserMatchIndex instances are created ad hoc per save.
     */
    private static final ConcurrentMap<UUID, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(UUID uuid) {
        return FILE_LOCKS.computeIfAbsent(uuid, __ -> new Object());
    }

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
        synchronized (lockFor(uuid)) {
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
                // Also drop the path mappings of the trimmed entries, or the paths section grows forever.
                for (String dropped : matches.subList(cap, matches.size())) {
                    if (dropped != null && !dropped.isBlank()) yml.set("paths." + dropped, null);
                }
                matches = matches.subList(0, cap);
            }

            yml.set("matches", matches);

            try {
                yml.save(f);
            } catch (IOException e) {
                plugin.getLogger().warning("Matchbook: failed updating user index " + f.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Legacy helper for older call sites that only had a relative path.
     * Prefer {@link #addMatchForPlayer(UUID, String, String)}.
     */
    public void addMatchForPlayer(UUID uuid, String legacyRelativeMatchPath) {
        synchronized (lockFor(uuid)) {
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
    }

    /** Stored display-timezone override (an IANA zone id), or null if the player has none. */
    public String getTimezone(UUID uuid) {
        synchronized (lockFor(uuid)) {
            File f = new File(usersDir(), uuid.toString() + ".yml");
            if (!f.exists()) return null;
            return YamlConfiguration.loadConfiguration(f).getString("timezone", null);
        }
    }

    /** Sets (or with null, clears) the player's display-timezone override. */
    public void setTimezone(UUID uuid, String zoneId) {
        synchronized (lockFor(uuid)) {
            File f = new File(usersDir(), uuid.toString() + ".yml");
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            yml.set("timezone", zoneId);
            try {
                yml.save(f);
            } catch (IOException e) {
                plugin.getLogger().warning("Matchbook: failed saving timezone to " + f.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }

    public List<String> getMatchIdsForPlayer(UUID uuid) {
        synchronized (lockFor(uuid)) {
            File f = new File(usersDir(), uuid.toString() + ".yml");
            if (!f.exists()) return List.of();

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            return new ArrayList<>(yml.getStringList("matches"));
        }
    }
}
