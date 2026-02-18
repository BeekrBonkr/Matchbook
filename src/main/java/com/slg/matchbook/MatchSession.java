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
    public final long startUnix;
    public volatile Long endUnix = null;
    public final String matchId;

    public final Set<de.marcely.bedwars.api.arena.Team> bedLostTeams = new HashSet<>();

    // Debug/proof of timing
    public volatile Long startSnapshotTakenUnix = null;

    // Result captured from ArenaWinningTeamDetermineEvent (preferred)
    // Examples: "WIN:BLUE", "TIE"
    public volatile String result = null;
    public volatile Team winningTeam = null;

    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

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

    public Set<UUID> getParticipants() {
        return participants;
    }

    public void setTeam(UUID uuid, Team team) {
        if (team != null) teamByPlayer.put(uuid, team);
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
}
