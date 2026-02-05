package com.slg.matchbook;

import de.marcely.bedwars.api.arena.Team;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MatchSession {

    public final String arenaName;
    public final long startUnix;
    public volatile Long endUnix = null;

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

    private final ConcurrentMap<UUID, Long> startTakenUnixByPlayer = new ConcurrentHashMap<>();

    public MatchSession(String arenaName, long startUnix) {
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

    public void setStartTakenUnix(UUID uuid, long unix) {
        startTakenUnixByPlayer.put(uuid, unix);
    }

    public Long getStartTakenUnix(UUID uuid) {
        return startTakenUnixByPlayer.get(uuid);
    }
}
