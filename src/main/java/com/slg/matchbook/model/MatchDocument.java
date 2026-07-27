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
        /** Matchbook version that recorded the match (captured at session creation, not at export). */
        String matchbookVersion,
        Long startSnapshotTakenUnix,
        List<UUID> participants,
        List<UUID> spectators,
        Map<UUID, String> spectatorUsernames,
        Map<UUID, PlayerEntry> players,
        List<String> warnings,
        List<MatchEvent> events,
        List<String> tiedTeams
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
        // Which teams actually tied for 1st. A match-wide "TIE" result does not mean every team tied —
        // a team eliminated earlier in a 3+-team match still lost outright even if two OTHER teams
        // ended up tied for 1st. Must not stamp matchbook:ties on players outside this set.
        List<String> tiedTeamNames = session.getTiedTeamNames();
        // Teams holding 1st place. A "TIE" result with only ONE team at 1st is not a tie between
        // anybody — it's a match whose winner MBedwars failed to report (see the reconciliation in
        // MatchLifecycleService). MatchLifecycleService corrects the result before saving, but the
        // abort/flush paths build a document without ever finalizing placements, so guard here too:
        // a team that finished 1st must never be stamped with a loss.
        List<String> firstPlaceTeamNames = session.getFirstPlaceTeamNames();

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

            // Whether THIS player's team is one of the teams actually tied for 1st (not just
            // "the match happened to end in a tie" — see tiedTeamNames comment above).
            boolean myTeamTied = tie && team != null && tiedTeamNames.contains(team.name());

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
                } else if (myTeamTied) {
                    // Ties count as neither a win nor a loss — prevent MBedwars values from leaking through.
                    if (diff.containsKey("bedwars:wins")) diff.put("bedwars:wins", 0L);
                    if (diff.containsKey("bedwars:loses")) diff.put("bedwars:loses", 0L);
                } else if (tie && team != null && !firstPlaceTeamNames.contains(team.name())) {
                    // Match-wide tie between OTHER teams; this team was outright eliminated earlier
                    // and did not tie for anything, so it's still a loss.
                    //
                    // Teams that finished 1st are excluded: when a "TIE" result has a single 1st-place
                    // team, that team didn't lose, it won a match MBedwars didn't report a winner for.
                    // Stamping it here produced records holding matchbook:1st_place AND bedwars:loses=1
                    // at the same time. Its win/loss values are left exactly as MBedwars recorded them
                    // rather than fabricated, since this path only runs for documents built without a
                    // finalized result.
                    long winsDiff = diff.getOrDefault("bedwars:wins", 0L);
                    long losesDiff = diff.getOrDefault("bedwars:loses", 0L);
                    if (losesDiff == 0L) diff.put("bedwars:loses", 1L);
                    if (winsDiff != 0L) diff.put("bedwars:wins", 0L);
                }
            }

            // Track ties as a first-class stat (mirrors how matchbook:*_place keys work) — only for
            // players on a team that actually tied for 1st, not every participant in the match.
            if (myTeamTied) {
                if (diff == null) diff = new LinkedHashMap<>();
                diff.put("matchbook:ties", 1L);
            }

            players.put(u, new PlayerEntry(u, username, team, startTaken, start, end, diff));
        }

        // Kill attributions that never found their death row become legacy PLAYER_KILL rows
        // instead of being dropped (idempotent; safe if fromSession runs more than once).
        session.flushPendingKills();
        List<MatchEvent> events = session.getEvents();
        List<String> tiedTeams = tiedTeamNames;

        return new MatchDocument(
                session.matchId,
                session.arenaName,
                session.startUnix,
                endUnix,
                result != null ? result : "UNKNOWN",
                session.matchbookVersion,
                session.startSnapshotTakenUnix,
                List.copyOf(participants),
                List.copyOf(spectators),
                Collections.unmodifiableMap(spectatorUsernames),
                Collections.unmodifiableMap(players),
                warnings.isEmpty() ? null : List.copyOf(warnings),
                events.isEmpty() ? null : List.copyOf(events),
                tiedTeams.isEmpty() ? null : List.copyOf(tiedTeams)
        );
    }
}
