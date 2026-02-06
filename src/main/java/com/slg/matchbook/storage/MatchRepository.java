package com.slg.matchbook.storage;

import com.slg.matchbook.MatchSession;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface MatchRepository {

    void init() throws Exception;
    void shutdown();

    // Save a match from the in-memory session + result string ("WIN:RED" / "TIE")
    void saveMatch(MatchSession session, String result) throws IOException;

    // Used by exporter/GUI
    File findMatchFileById(String matchId);              // YAML mode returns actual file; MySQL mode can return null
    YamlConfiguration loadMatchYaml(String matchId);     // YAML loads from file; MySQL builds YamlConfiguration from DB

    List<String> listMatchIdsForPlayer(UUID playerUuid); // for history GUI

    /**
     * List all known match IDs, sorted most-recent-first (by start_unix where available).
     * Used by /mb all and for admin review.
     */
    List<String> listAllMatchIds();
}
