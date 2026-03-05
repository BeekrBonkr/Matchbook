package com.slg.matchbook.io;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.StatSnapshot;
import com.slg.matchbook.model.MatchDocument;
import de.marcely.bedwars.api.arena.Team;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

/**
 * Single source of truth for the on-disk / in-DB YAML match document.
 *
 * Why this exists:
 *  - YAML mode writes files
 *  - MySQL mode stores the same YAML as LONGTEXT
 *  - Migration moves YAML between the two
 *
 * If we ever change the schema, we want to change it once.
 */
public final class MatchYamlCodec {

    private MatchYamlCodec() {}

    public static YamlConfiguration toYaml(MatchSession session, String result) {
        return toYaml(MatchDocument.fromSession(session, result));
    }

    public static YamlConfiguration toYaml(MatchDocument doc) {
        Objects.requireNonNull(doc, "doc");

        YamlConfiguration yml = new YamlConfiguration();

        yml.set("match.match_id", doc.matchId());
        yml.set("match.start_unix", doc.startUnix());
        yml.set("match.end_unix", doc.endUnix());
        yml.set("match.arena", doc.arenaName());
        yml.set("match.result", doc.result());
        yml.set("match.start_snapshot_taken_unix", doc.startSnapshotTakenUnix());

        List<String> participants = new ArrayList<>();
        for (UUID u : doc.participants()) participants.add(u.toString());
        yml.set("match.participants", participants);

        List<String> spectators = new ArrayList<>();
        if (doc.spectators() != null) {
            for (UUID u : doc.spectators()) spectators.add(u.toString());
        }
        yml.set("match.spectators", spectators);

        // Store spectator usernames for GUI display.
        if (doc.spectatorUsernames() != null) {
            for (var e : doc.spectatorUsernames().entrySet()) {
                if (e.getKey() == null) continue;
                String base = "spectators." + e.getKey();
                yml.set(base + ".username", e.getValue());
            }
        }

        for (MatchDocument.PlayerEntry e : doc.players().values()) {
            String base = "players." + e.uuid();
            yml.set(base + ".username", e.username());
            yml.set(base + ".team", e.team() != null ? e.team().name() : null);
            yml.set(base + ".start_taken_unix", e.startTakenUnix());

            if (e.start() != null) yml.createSection(base + ".start", e.start());
            if (e.end() != null) yml.createSection(base + ".end", e.end());
            if (e.diff() != null) yml.createSection(base + ".diff", e.diff());
        }

        if (doc.warnings() != null && !doc.warnings().isEmpty()) {
            yml.set("match.warnings", doc.warnings());
        }

        return yml;
    }

    public static String toYamlString(MatchSession session, String result) {
        return toYaml(session, result).saveToString();
    }

    public static String toYamlString(MatchDocument doc) {
        return toYaml(doc).saveToString();
    }
}
