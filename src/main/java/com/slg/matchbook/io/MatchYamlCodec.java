package com.slg.matchbook.io;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.StatSnapshot;
import com.slg.matchbook.model.MatchDocument;
import com.slg.matchbook.model.MatchEvent;
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
        if (doc.tiedTeams() != null && !doc.tiedTeams().isEmpty()) {
            yml.set("match.tied_teams", doc.tiedTeams());
        }

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
            // Persist the arena's actual configured bed/wool color for this team (not just the enum
            // name) so the GUI renders the right color even when an admin has recolored a team, and
            // so old data stays self-contained if MBedwars' defaults change later.
            if (e.team() != null) {
                org.bukkit.DyeColor dye = null;
                try {
                    dye = e.team().getDyeColor();
                } catch (Throwable ignored) {
                    // Defensive: some MBedwars builds/team configs may not expose a dye color.
                }
                if (dye != null) yml.set(base + ".team_color", dye.name());
            }
            yml.set(base + ".start_taken_unix", e.startTakenUnix());

            if (e.start() != null) yml.createSection(base + ".start", e.start());
            if (e.end() != null) yml.createSection(base + ".end", e.end());
            if (e.diff() != null) yml.createSection(base + ".diff", e.diff());
        }

        if (doc.warnings() != null && !doc.warnings().isEmpty()) {
            yml.set("match.warnings", doc.warnings());
        }

        // Event log
        if (doc.events() != null && !doc.events().isEmpty()) {
            long startUnix = doc.startUnix();
            List<Map<String, Object>> eventList = new ArrayList<>();
            for (MatchEvent ev : doc.events()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", ev.type().name());
                m.put("timestamp", ev.timestamp());
                m.put("offset", ev.offsetSeconds(startUnix));
                if (ev.playerUuid() != null)  m.put("player_uuid", ev.playerUuid());
                if (ev.playerName() != null)  m.put("player_name", ev.playerName());
                if (ev.playerTeam() != null)  m.put("player_team", ev.playerTeam());
                if (ev.killerUuid() != null)  m.put("killer_uuid", ev.killerUuid());
                if (ev.killerName() != null)  m.put("killer_name", ev.killerName());
                if (ev.killerTeam() != null)  m.put("killer_team", ev.killerTeam());
                if (ev.bedTeam() != null)     m.put("bed_team", ev.bedTeam());
                if (ev.isFinal())             m.put("final", true);
                if (ev.deathCause() != null)  m.put("cause", ev.deathCause());
                if (ev.wasSpectating())       m.put("was_spectating", true);
                eventList.add(m);
            }
            yml.set("events", eventList);
        }

        return yml;
    }

    /** Parse the events section from a loaded match YAML. Returns empty list if absent. */
    public static List<Map<String, Object>> readRawEvents(YamlConfiguration yml) {
        if (yml == null) return List.of();
        List<?> raw = yml.getList("events");
        if (raw == null || raw.isEmpty()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object obj : raw) {
            if (!(obj instanceof Map<?, ?> src)) continue;
            Map<String, Object> ev = new LinkedHashMap<>();
            for (var entry : src.entrySet()) {
                if (entry.getKey() != null) ev.put(entry.getKey().toString(), entry.getValue());
            }
            out.add(ev);
        }
        return out;
    }

    public static String toYamlString(MatchSession session, String result) {
        return toYaml(session, result).saveToString();
    }

    public static String toYamlString(MatchDocument doc) {
        return toYaml(doc).saveToString();
    }
}
