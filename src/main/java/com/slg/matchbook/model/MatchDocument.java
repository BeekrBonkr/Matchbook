package com.slg.matchbook.model;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.StatSnapshot;
import de.marcely.bedwars.api.arena.Team;

import java.util.*;

/**
 * A storage/GUI/export-friendly representation of a finished match.
 *
 * This is intentionally decoupled from Arena live objects so we can safely save/export asynchronously.
 */
public record MatchDocument(
        String matchId,
        String arenaName,
        long startUnix,
        long endUnix,
        String result,
        Long startSnapshotTakenUnix,
        List<UUID> participants,
        Map<UUID, PlayerEntry> players,
        List<String> warnings
) {

    public record PlayerEntry(
            UUID uuid,
            String username,
            Team team,
            Long startTakenUnix,
            Map<String, Long> start,
            Map<String, Long> end,
            Map<String, Long> diff
    ) {}

    public static MatchDocument fromSession(MatchSession session, String result) {
        Objects.requireNonNull(session, "session");

        long endUnix = session.endUnix != null ? session.endUnix : (System.currentTimeMillis() / 1000L);

        List<UUID> participants = new ArrayList<>(session.getParticipants());
        // stable order
        participants.sort(Comparator.comparing(UUID::toString));

        Map<UUID, PlayerEntry> players = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        boolean tie = result != null && result.equalsIgnoreCase("TIE");
        Team winningTeam = session.winningTeam;

        for (UUID u : participants) {
            String username = session.getUsername(u);
            Team team = session.getTeam(u);
            Long startTaken = session.getStartTakenUnix(u);

            StatSnapshot startSnap = session.getStart(u);
            StatSnapshot endSnap = session.getEnd(u);

            Map<String, Long> start = startSnap != null ? new LinkedHashMap<>(startSnap.values()) : null;
            Map<String, Long> end = endSnap != null ? new LinkedHashMap<>(endSnap.values()) : null;

            Map<String, Long> diff = null;
            if (startSnap != null && endSnap != null) {
                diff = new LinkedHashMap<>(StatSnapshot.diff(startSnap, endSnap));

                // Clamp negative diffs, annotate warning
                for (String key : new ArrayList<>(diff.keySet())) {
                    long v = diff.getOrDefault(key, 0L);
                    if (v < 0L) {
                        warnings.add("negative_diff " + u + " " + key + "=" + v);
                        diff.put(key, 0L);
                    }
                }

                // Win/loss enforcement (same as codec previously)
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
            }

            players.put(u, new PlayerEntry(u, username, team, startTaken, start, end, diff));
        }

        return new MatchDocument(
                session.matchId,
                session.arenaName,
                session.startUnix,
                endUnix,
                result != null ? result : "UNKNOWN",
                session.startSnapshotTakenUnix,
                List.copyOf(participants),
                Collections.unmodifiableMap(players),
                warnings.isEmpty() ? null : List.copyOf(warnings)
        );
    }
}
