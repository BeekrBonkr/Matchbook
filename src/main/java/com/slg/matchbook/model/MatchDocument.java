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
 *
 * Since 0.7.5 each player's stats row (the "diff" map) is derived from the recorded event log — the
 * event log is the source of truth for what happened in the match. MBedwars' counter snapshots are
 * still captured, but only as a diagnostic cross-check (see EVENT_DERIVED_KEYS).
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

    /**
     * Stat keys whose exported values are derived from the recorded event log, never from MBedwars'
     * counters. The event log is the reliable record of what happened in a match; the counter
     * snapshot path has several independent failure modes (unreset per-round counters, roster
     * contamination from the arena's next lobby, trigger/counter max-merging) that repeatedly
     * produced stats rows the event log couldn't back up. Counters are still captured and compared
     * against these — a disagreement is recorded as a "stat_mismatch" warning on the match.
     */
    private static final List<String> EVENT_DERIVED_KEYS = List.of(
            "bedwars:kills",
            "bedwars:final_kills",
            "bedwars:deaths",
            "bedwars:final_deaths",
            "bedwars:beds_destroyed",
            "bedwars:beds_lost");

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

        // Kill attributions that never found their death row become legacy PLAYER_KILL rows
        // (idempotent; safe if fromSession runs more than once). Must happen BEFORE stats are
        // derived below so those kills are counted.
        session.flushPendingKills();
        List<MatchEvent> events = session.getEvents();

        // ------------------------------------------------------------------
        // Derive per-player stats from the event log (single pass).
        //
        // - deaths / final_deaths: the player's PLAYER_DEATH rows, skipping rows MBedwars flagged
        //   as not stat-counting (statsUncounted — the row stays in the log either way).
        // - kills / final_kills: PLAYER_DEATH rows attributing the death to the player, plus legacy
        //   standalone PLAYER_KILL rows. An attribution is only ever recorded for a kill MBedwars
        //   said counts, so no counting flag is needed on this side.
        // - beds_destroyed: the player's BED_BREAK rows.
        // - beds_lost: BED_BREAK rows whose bed_team is the player's team.
        // ------------------------------------------------------------------
        Map<String, Map<String, Long>> derivedByUuid = new HashMap<>();
        Map<String, Long> bedsLostByTeam = new HashMap<>();
        Set<String> uuidsSeenInEvents = new HashSet<>();
        for (MatchEvent ev : events) {
            if (ev == null) continue;
            if (ev.playerUuid() != null) uuidsSeenInEvents.add(ev.playerUuid());
            if (ev.killerUuid() != null) uuidsSeenInEvents.add(ev.killerUuid());
            switch (ev.type()) {
                case PLAYER_DEATH -> {
                    if (ev.playerUuid() != null && !ev.statsUncounted()) {
                        bump(derivedByUuid, ev.playerUuid(), "bedwars:deaths");
                        if (ev.isFinal()) bump(derivedByUuid, ev.playerUuid(), "bedwars:final_deaths");
                    }
                    if (ev.killerUuid() != null) {
                        bump(derivedByUuid, ev.killerUuid(), "bedwars:kills");
                        if (ev.isFinal()) bump(derivedByUuid, ev.killerUuid(), "bedwars:final_kills");
                    }
                }
                case PLAYER_KILL -> {
                    if (ev.killerUuid() != null) {
                        bump(derivedByUuid, ev.killerUuid(), "bedwars:kills");
                        if (ev.isFinal()) bump(derivedByUuid, ev.killerUuid(), "bedwars:final_kills");
                    }
                }
                case BED_BREAK -> {
                    if (ev.playerUuid() != null) bump(derivedByUuid, ev.playerUuid(), "bedwars:beds_destroyed");
                    if (ev.bedTeam() != null) bedsLostByTeam.merge(ev.bedTeam(), 1L, Long::sum);
                }
                default -> { }
            }
        }

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

        List<UUID> keptParticipants = new ArrayList<>();

        for (UUID u : participants) {
            String username = session.getUsername(u);
            Team team = session.getTeam(u);
            Long startTaken = session.getStartTakenUnix(u);
            String uuidStr = u.toString();

            // A participant with no team that the event log never mentions was never actually in
            // this match — a roster-capture artifact (e.g. a player queueing into this arena's next
            // round while this one was ending). Keeping them produced the "teamless player with
            // stats carried over from another round" rows. Dropped, with a paper trail.
            if (team == null && !uuidsSeenInEvents.contains(uuidStr)) {
                warnings.add("phantom_participant_dropped " + u + (username != null ? " " + username : ""));
                continue;
            }
            keptParticipants.add(u);

            StatSnapshot startSnap = session.getStart(u);
            StatSnapshot endSnap = session.getEnd(u);
            StatSnapshot matchSnap = session.getMatchStats(u);

            Map<String, Long> start = startSnap != null ? new LinkedHashMap<>(startSnap.values()) : null;
            Map<String, Long> end = endSnap != null ? new LinkedHashMap<>(endSnap.values()) : null;

            // The exported stats row: event-derived keys first (explicitly zeroed so every row is
            // complete and baselined to 0), wins/loses seeded for the enforcement below.
            Map<String, Long> diff = new LinkedHashMap<>();
            for (String k : EVENT_DERIVED_KEYS) diff.put(k, 0L);
            diff.put("bedwars:wins", 0L);
            diff.put("bedwars:loses", 0L);
            Map<String, Long> fromEvents = derivedByUuid.get(uuidStr);
            if (fromEvents != null) diff.putAll(fromEvents);
            if (team != null) diff.put("bedwars:beds_lost", bedsLostByTeam.getOrDefault(team.name(), 0L));

            // Cross-check: the counter snapshot (the pre-0.7.5 source of truth) is now diagnostic.
            // A key it disagrees on is exactly the corruption this derivation exists to fix — record
            // it so the discrepancy is visible in the match document instead of in the exported data.
            if (matchSnap != null) {
                for (String k : EVENT_DERIVED_KEYS) {
                    Long counter = matchSnap.values().get(k);
                    long derived = diff.getOrDefault(k, 0L);
                    if (counter != null && counter != derived) {
                        warnings.add("stat_mismatch " + u + " " + k + " events=" + derived + " counters=" + counter);
                    }
                }
            }

            // Keys the event log has no record of — matchbook:*_place / matchbook:ties (written into
            // the counter snapshot by applyPlacementsToMatchStats) and any custom tracked keys an
            // admin added (e.g. resource stats) — still come from the snapshot path.
            if (matchSnap != null) {
                for (var en : matchSnap.values().entrySet()) {
                    String k = en.getKey();
                    if (k == null || diff.containsKey(k)) continue;
                    diff.put(k, en.getValue());
                }
            }

            // Whether THIS player's team is one of the teams actually tied for 1st (not just
            // "the match happened to end in a tie" — see tiedTeamNames comment above).
            boolean myTeamTied = tie && team != null && tiedTeamNames.contains(team.name());

            // Win/loss: derivable from the finalized result/placements in every branch below except
            // the no-winner-no-tie leftovers (aborted/UNKNOWN matches, and a "TIE" whose single
            // 1st-place team means MBedwars just failed to report the winner). Only there do the
            // MBedwars-recorded values pass through, exactly as before.
            boolean winLossDerivable = (!tie && winningTeam != null && team != null)
                    || myTeamTied
                    || (tie && team != null && !firstPlaceTeamNames.contains(team.name()));
            if (!winLossDerivable && matchSnap != null) {
                Long wins = matchSnap.values().get("bedwars:wins");
                Long loses = matchSnap.values().get("bedwars:loses");
                if (wins != null) diff.put("bedwars:wins", wins);
                if (loses != null) diff.put("bedwars:loses", loses);
            }

            {
                // Clamp negative values (only possible on snapshot-sourced keys), annotate warning
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
                diff.put("matchbook:ties", 1L);
            }

            players.put(u, new PlayerEntry(u, username, team, startTaken, start, end, diff));
        }

        List<String> tiedTeams = tiedTeamNames;

        return new MatchDocument(
                session.matchId,
                session.arenaName,
                session.startUnix,
                endUnix,
                result != null ? result : "UNKNOWN",
                session.matchbookVersion,
                session.startSnapshotTakenUnix,
                List.copyOf(keptParticipants),
                List.copyOf(spectators),
                Collections.unmodifiableMap(spectatorUsernames),
                Collections.unmodifiableMap(players),
                warnings.isEmpty() ? null : List.copyOf(warnings),
                events.isEmpty() ? null : List.copyOf(events),
                tiedTeams.isEmpty() ? null : List.copyOf(tiedTeams)
        );
    }

    private static void bump(Map<String, Map<String, Long>> byUuid, String uuid, String key) {
        byUuid.computeIfAbsent(uuid, __ -> new HashMap<>()).merge(key, 1L, Long::sum);
    }
}
