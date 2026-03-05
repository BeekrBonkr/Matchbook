package com.slg.matchbook.storage;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.model.MatchDocument;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface MatchRepository {

    void init() throws Exception;
    void shutdown();

    /**
     * Save a match document to the configured backend.
     */
    void saveMatch(MatchDocument doc) throws IOException;

    /**
     * Back-compat convenience for in-memory sessions.
     */
    default void saveMatch(MatchSession session, String result) throws IOException {
        saveMatch(MatchDocument.fromSession(session, result));
    }

    // Used by exporter/GUI
    File findMatchFileById(String matchId);              // YAML mode returns actual file; MySQL mode can return null
    YamlConfiguration loadMatchYaml(String matchId);     // YAML loads from file; MySQL builds YamlConfiguration from DB

    List<String> listMatchIdsForPlayer(UUID playerUuid); // for history GUI

    /**
     * List all known match IDs, sorted most-recent-first (by start_unix where available).
     * Used by /mb all and for admin review.
     */
    List<String> listAllMatchIds();

    /**
     * Lightweight health check for admin tooling.
     * Implementations should avoid heavy work; a simple "can I read/write" or "SELECT 1" is ideal.
     */
    default HealthCheckResult healthCheck() {
        return new HealthCheckResult(true, "OK");
    }
}
