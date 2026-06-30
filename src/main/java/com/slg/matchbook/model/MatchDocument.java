package com.slg.matchbook.model;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.StatSnapshot;
import de.marcely.bedwars.api.arena.Team;

import java.util.*;
import java.util.stream.Collectors;

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
        List<UUID> spectators,
        Map<UUID, String> spectatorUsernames,
        Map<UUID, PlayerEntry> players,
        List<String> warnings,
        List<MatchEvent> events
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

        // Exclude "spectator-only" viewers (never on a team).
        List<UUID> participants = new ArrayList<>();
        for (UUID u : session.getParticipants()) {
            if (session.isSpectatorOnly(u)) continue;
            participants.add(u);
        }
        // stable order
        participants.sort(Comparator.comparing(UUID::toString));

        // Spectator-only viewers (never on a team) are tracked separately.
        List<UUID> spectators = new ArrayList<>();
        for (UUID u : session.getSpectatorOnly()) {
            if (session.isSpectatorOnly(u)) spectators.add(u);
        }
        spectators.sort(Comparator.comparing(UUID::toString));

        // Preserve usernames for spectators (for GUI display).
        Map<UUID, String> spectatorUsernames = new LinkedHashMap<>();
        for (UUID u : spectators) {
            String name = session.getUsername(u);
            spectatorUsernames.put(u, (name != null && !name.isBlank()) ? name : u.toString());
        }

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
            StatSnapshot matchSnap = session.getMatchStats(u);

            Map<String, Long> start = startSnap != null ? new LinkedHashMap<>(startSnap.values()) : null;
            Map<String, Long> end = endSnap != null ? new LinkedHashMap<>(endSnap.values()) : null;

            Map<String, Long> diff = null;

            // Prefer per-match stats (game stats / quit memories) when available.
            if (matchSnap != null) {
                diff = new LinkedHashMap<>(matchSnap.values());
            } else if (startSnap != null && endSnap != null) {
                diff = new LinkedHashMap<>(StatSnapshot.diff(startSnap, endSnap));
            }

            if (diff != null) {
                // Clamp negative diffs, annotate warning
                for (String key : new ArrayList<>(diff.keySet())) {
                    long v = diff.getOrDefault(key, 0L);
                    if (v < 0L) {
                        warnings.add("negative_diff " + u + " " + key + "=" + v);
                        diff.put(key, 0L);
                    }
                }

                // Win/loss enforcement
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
                } else if (tie) {
                    // Ties count as neither a win nor a loss — prevent MBedwars values from leaking through.
                    if (diff.containsKey("bedwars:wins")) diff.put("bedwars:wins", 0L);
                    if (diff.containsKey("bedwars:loses")) diff.put("bedwars:loses", 0L);
                }
            }

            // Track ties as a first-class stat (mirrors how matchbook:*_place keys work).
            if (tie) {
                if (diff == null) diff = new LinkedHashMap<>();
                diff.put("matchbook:ties", 1L);
            }

            players.put(u, new PlayerEntry(u, username, team, startTaken, start, end, diff));
        }

        List<MatchEvent> events = session.getEvents();

        return new MatchDocument(
                session.matchId,
                session.arenaName,
                session.startUnix,
                endUnix,
                result != null ? result : "UNKNOWN",
                session.startSnapshotTakenUnix,
                List.copyOf(participants),
                List.copyOf(spectators),
                Collections.unmodifiableMap(spectatorUsernames),
                Collections.unmodifiableMap(players),
                warnings.isEmpty() ? null : List.copyOf(warnings),
                events.isEmpty() ? null : List.copyOf(events)
        );
    }
}
