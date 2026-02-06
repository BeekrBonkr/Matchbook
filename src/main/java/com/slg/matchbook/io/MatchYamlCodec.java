package com.slg.matchbook.io;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.StatSnapshot;
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
        Objects.requireNonNull(session, "session");

        YamlConfiguration yml = new YamlConfiguration();

        long endUnix = session.endUnix != null ? session.endUnix : (System.currentTimeMillis() / 1000L);

        yml.set("match.match_id", session.matchId);
        yml.set("match.start_unix", session.startUnix);
        yml.set("match.end_unix", endUnix);
        yml.set("match.arena", session.arenaName);
        yml.set("match.result", result != null ? result : "UNKNOWN");
        yml.set("match.start_snapshot_taken_unix", session.startSnapshotTakenUnix);

        List<String> participants = new ArrayList<>();
        for (UUID u : session.getParticipants()) participants.add(u.toString());
        yml.set("match.participants", participants);

        // Optional diagnostics. These are intentionally informational and do not affect exports.
        List<String> warnings = new ArrayList<>();

        Team winningTeam = session.winningTeam;
        boolean tie = result != null && result.equalsIgnoreCase("TIE");

        for (UUID u : session.getParticipants()) {
            String base = "players." + u;

            yml.set(base + ".username", session.getUsername(u));

            Team team = session.getTeam(u);
            yml.set(base + ".team", team != null ? team.name() : null);

            yml.set(base + ".start_taken_unix", session.getStartTakenUnix(u));

            StatSnapshot start = session.getStart(u);
            StatSnapshot end = session.getEnd(u);

            if (start != null) yml.createSection(base + ".start", start.values());
            if (end != null) yml.createSection(base + ".end", end.values());

            if (start != null && end != null) {
                Map<String, Long> diff = new LinkedHashMap<>(StatSnapshot.diff(start, end));

                // Guardrail: diff should never be negative. If it is, clamp to 0 and annotate.
                // This can happen if MBedwars resets a stat scope between snapshots or a stat key changes.
                for (String key : new ArrayList<>(diff.keySet())) {
                    long v = diff.getOrDefault(key, 0L);
                    if (v < 0L) {
                        warnings.add("negative_diff " + u + " " + key + "=" + v);
                        diff.put(key, 0L);
                    }
                }

                // Enforce win/lose outcome if MBedwars hasn't persisted by snapshot time.
                if (!tie && winningTeam != null && team != null) {
                    long winsDiff = diff.getOrDefault("bedwars:wins", 0L);
                    long losesDiff = diff.getOrDefault("bedwars:loses", 0L);

                    if (team == winningTeam) {
                        if (winsDiff == 0L) diff.put("bedwars:wins", 1L);
                        if (losesDiff != 0L) diff.put("bedwars:loses", 0L);
                    } else {
                        if (losesDiff == 0L) diff.put("bedwars:loses", 1L);
                        if (winsDiff != 0L) diff.put("bedwars:wins", 0L);
                    }
                }

                yml.createSection(base + ".diff", diff);
            }
        }

        if (!warnings.isEmpty()) {
            yml.set("match.warnings", warnings);
        }

        return yml;
    }

    public static String toYamlString(MatchSession session, String result) {
        return toYaml(session, result).saveToString();
    }
}
