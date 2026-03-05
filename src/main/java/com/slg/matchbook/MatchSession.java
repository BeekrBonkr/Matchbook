package com.slg.matchbook;

import de.marcely.bedwars.api.arena.Team;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class MatchSession {

    public final String arenaName;
    /**
     * Start timestamp in unix seconds.
     *
     * NOTE: This is mutable because we may create a session early (e.g., when the first player joins)
     * so placeholders can resolve during pre-round phases, then overwrite the start time when the
     * round actually begins.
     */
    public volatile long startUnix;
    public volatile Long endUnix = null;
    public final String matchId;

    public final Set<de.marcely.bedwars.api.arena.Team> bedLostTeams = new HashSet<>();

    // ---------------------------
    // Placement tracking
    // ---------------------------

    /** Teams that participated in this match (derived from player membership). */
    public final Set<Team> participatingTeams = new HashSet<>();

    /**
     * Alive players (by UUID) per team. Players are removed on fatal (final) death.
     * This is only used for placement detection.
     */
    public final ConcurrentMap<Team, Set<UUID>> alivePlayersByTeam = new ConcurrentHashMap<>();

    /** Team -> placement rank (1 = 1st, 2 = 2nd, ...). */
    public final ConcurrentMap<Team, Integer> placementByTeam = new ConcurrentHashMap<>();

    /** Elimination order of teams (first eliminated first). */
    public final java.util.List<Team> eliminationOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    // Debug/proof of timing
    public volatile Long startSnapshotTakenUnix = null;

    // Result captured from ArenaWinningTeamDetermineEvent (preferred)
    // Examples: "WIN:BLUE", "TIE"
    public volatile String result = null;
    public volatile Team winningTeam = null;

    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    /**
     * Players who joined the arena as spectators (watching) and were never part of a team.
     *
     * Important distinction:
     *  - A real participant who later dies will become a spectator in-game, but MUST still count.
     *  - A "spectator-only" viewer (never on a team) must NOT be included in match stats/exports.
     */
    private final Set<UUID> spectatorOnly = ConcurrentHashMap.newKeySet();

    /**
     * Players who have entered the arena context (typically lobby) but have NOT yet been assigned a team.
     *
     * This is important because MBedwars can briefly report team == null during lobby/assignment.
     * We don't want to count these as participants unless they actually get a team or generate match activity.
     */
    private final Set<UUID> pendingParticipants = ConcurrentHashMap.newKeySet();


    private final ConcurrentMap<UUID, Team> teamByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> usernameByPlayer = new ConcurrentHashMap<>();

    private final ConcurrentMap<UUID, StatSnapshot> startStats = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, StatSnapshot> endStats = new ConcurrentHashMap<>();

    // Per-match stats (prefer this over start/end diffs when available)
    private final ConcurrentMap<UUID, StatSnapshot> matchStats = new ConcurrentHashMap<>();

    private final ConcurrentMap<UUID, Long> startTakenUnixByPlayer = new ConcurrentHashMap<>();

    // Reliable per-match counters based on MBedwars events.
    // This is used as a backstop for key stats (kills/deaths/bed breaks) and as the persistence trigger.
    private final ConcurrentMap<UUID, ConcurrentMap<String, LongAdder>> triggerIncrements = new ConcurrentHashMap<>();
    private final AtomicBoolean triggerActivity = new AtomicBoolean(false);

    public MatchSession(String matchId, String arenaName, long startUnix) {
        this.matchId = matchId;
        this.arenaName = arenaName;
        this.startUnix = startUnix;
    }

    public void addParticipant(UUID uuid) {
        participants.add(uuid);
    }

    public void markSpectatorOnly(UUID uuid) {
        if (uuid == null) return;
        spectatorOnly.add(uuid);
    }

    public void unmarkSpectatorOnly(UUID uuid) {
        if (uuid == null) return;
        spectatorOnly.remove(uuid);
    }

    public boolean isSpectatorOnly(UUID uuid) {
        return uuid != null && spectatorOnly.contains(uuid);
    }

    
    public void markPending(UUID uuid) {
        if (uuid == null) return;
        pendingParticipants.add(uuid);
    }

    public void unmarkPending(UUID uuid) {
        if (uuid == null) return;
        pendingParticipants.remove(uuid);
    }

    public boolean isPending(UUID uuid) {
        return uuid != null && pendingParticipants.contains(uuid);
    }

    public Set<UUID> getSpectatorOnly() {
        return spectatorOnly;
    }

    /**
     * Promote a player to a real participant.
     *
     * Rule: a "participant" is someone who has EVER had a team in this match (even if later spectating).
     */
    public void promoteToParticipant(UUID uuid, Team team) {
        if (uuid == null) return;
        unmarkSpectatorOnly(uuid);
        unmarkPending(uuid);
        addParticipant(uuid);
        if (team != null) setTeam(uuid, team);
    }

public Set<UUID> getParticipants() {
        return participants;
    }

    public void setTeam(UUID uuid, Team team) {
        if (uuid == null) return;
        if (team != null) {
            teamByPlayer.put(uuid, team);
            // If the player was pending or spectator-only, they are now a real participant.
            unmarkSpectatorOnly(uuid);
            unmarkPending(uuid);
            participants.add(uuid);
        }
    }

    public Team getTeam(UUID uuid) {
        return teamByPlayer.get(uuid);
    }

    public void setUsername(UUID uuid, String username) {
        if (username != null && !username.isBlank()) usernameByPlayer.put(uuid, username);
    }

    public String getUsername(UUID uuid) {
        return usernameByPlayer.get(uuid);
    }

    public void putStart(UUID uuid, StatSnapshot snap) {
        startStats.put(uuid, snap);
    }

    public void putEnd(UUID uuid, StatSnapshot snap) {
        endStats.put(uuid, snap);
    }

    public StatSnapshot getStart(UUID uuid) {
        return startStats.get(uuid);
    }

    public StatSnapshot getEnd(UUID uuid) {
        return endStats.get(uuid);
    }

    public void putMatchStats(UUID uuid, StatSnapshot snap) {
        if (snap != null) matchStats.put(uuid, snap);
    }

    public void removeMatchStats(UUID uuid) {
        matchStats.remove(uuid);
    }

    public StatSnapshot getMatchStats(UUID uuid) {
        return matchStats.get(uuid);
    }

    public ConcurrentMap<UUID, StatSnapshot> getAllMatchStats() {
        return matchStats;
    }

    /**
     * Marks that at least one "important" in-match stat occurred (kill/death/bed break),
     * and increments a per-player counter for that key.
     */
    public void addTriggerIncrement(UUID uuid, String key, long amount) {
        if (uuid == null || key == null || key.isBlank() || amount <= 0L) return;
        triggerActivity.set(true);
        triggerIncrements
                .computeIfAbsent(uuid, __ -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, __ -> new LongAdder())
                .add(amount);
    }

    public boolean hasTriggerActivity() {
        return triggerActivity.get();
    }

    public long getTriggerTotal(UUID uuid, String key) {
        ConcurrentMap<String, LongAdder> m = triggerIncrements.get(uuid);
        if (m == null) return 0L;
        LongAdder a = m.get(key);
        return a == null ? 0L : a.sum();
    }

    /**
     * Ensures the matchStats snapshot contains (at minimum) the recorded trigger totals for the given keys.
     *
     * MatchDocument treats matchStats as a "diff" map, so we write the totals directly.
     */
    public void applyTriggerIncrementsToMatchStats(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;

        for (var entry : triggerIncrements.entrySet()) {
            UUID uuid = entry.getKey();
            ConcurrentMap<String, LongAdder> inc = entry.getValue();
            if (inc == null) continue;

            StatSnapshot existing = matchStats.get(uuid);
            var out = existing != null ? new java.util.LinkedHashMap<>(existing.values()) : new java.util.LinkedHashMap<String, Long>();

            boolean changed = false;
            for (String k : keys) {
                LongAdder a = inc.get(k);
                if (a == null) continue;
                long v = a.sum();
                if (v <= 0L) continue;

                long current = out.getOrDefault(k, 0L);
                if (current < v) {
                    out.put(k, v);
                    changed = true;
                }
            }

            if (changed) {
                matchStats.put(uuid, new StatSnapshot(out));
            }
        }
    }

    public boolean hasAnyNonZeroMatchStats() {
        for (StatSnapshot snap : matchStats.values()) {
            if (snap != null && snap.anyNonZero()) return true;
        }
        return false;
    }

    public void setStartTakenUnix(UUID uuid, long unix) {
        startTakenUnixByPlayer.put(uuid, unix);
    }

    public Long getStartTakenUnix(UUID uuid) {
        return startTakenUnixByPlayer.get(uuid);
    }

    // ----------------------------------------------------------------------
    // Placement helpers
    // ----------------------------------------------------------------------

    public void markTeamParticipating(Team team) {
        if (team == null) return;
        participatingTeams.add(team);
    }

    public void markPlayerAlive(Team team, UUID uuid) {
        if (team == null || uuid == null) return;
        participatingTeams.add(team);
        alivePlayersByTeam
                .computeIfAbsent(team, __ -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(uuid);
    }

    public void markPlayerFinalDead(Team team, UUID uuid) {
        if (team == null || uuid == null) return;
        Set<UUID> alive = alivePlayersByTeam.get(team);
        if (alive != null) alive.remove(uuid);
    }

    public boolean isTeamFullyDead(Team team) {
        Set<UUID> alive = alivePlayersByTeam.get(team);
        return alive == null || alive.isEmpty();
    }

    public int totalTeams() {
        return Math.max(0, participatingTeams.size());
    }

    /**
     * Records a placement for a team if not already recorded.
     */
    public void setPlacementIfAbsent(Team team, int place) {
        if (team == null || place <= 0) return;
        placementByTeam.putIfAbsent(team, place);
    }

    /**
     * Force-set a placement (overwrites existing). Used for tie handling.
     */
    public void setPlacement(Team team, int place) {
        if (team == null || place <= 0) return;
        placementByTeam.put(team, place);
    }

    /**
     * Writes one-hot placement keys into matchStats for each participant.
     *
     * Keys are stored as:
     *   matchbook:1st_place, matchbook:2nd_place, ...
     *
     * This makes CSV multi-export aggregation "just work" by summation.
     */
    public void applyPlacementsToMatchStats() {
        if (placementByTeam.isEmpty()) return;

        for (UUID uuid : participants) {
            Team t = teamByPlayer.get(uuid);
            if (t == null) continue;
            Integer place = placementByTeam.get(t);
            if (place == null || place <= 0) continue;

            String key = "matchbook:" + ordinal(place) + "_place";
            StatSnapshot existing = matchStats.get(uuid);
            var out = existing != null ? new java.util.LinkedHashMap<>(existing.values()) : new java.util.LinkedHashMap<String, Long>();
            out.put(key, out.getOrDefault(key, 0L) + 1L);
            matchStats.put(uuid, new StatSnapshot(out));
        }
    }

    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
