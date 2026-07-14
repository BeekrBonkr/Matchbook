package com.slg.matchbook.model;

/**
 * A single in-match event captured during a live game.
 *
 * The offset field is computed relative to match start and baked in at serialization time
 * so old match files remain self-contained.
 */
public record MatchEvent(
        EventType type,
        long timestamp,     // wall-clock unix seconds when this event occurred
        String playerUuid,  // victim / joiner / breaker depending on type
        String playerName,
        String playerTeam,
        String killerUuid,  // only set for PLAYER_KILL
        String killerName,
        String killerTeam,
        String bedTeam,     // team whose bed was broken (BED_BREAK only)
        boolean isFinal,    // fatal death / final kill, depending on type
        String deathCause,  // Bukkit EntityDamageEvent.DamageCause name; PLAYER_DEATH / PLAYER_KILL only
        boolean wasSpectating // PLAYER_LEAVE only: true if already eliminated (spectating) before they quit
) {

    public enum EventType {
        MATCH_START,
        PLAYER_JOIN,
        PLAYER_LEAVE,
        PLAYER_DEATH,
        PLAYER_KILL,
        BED_BREAK,
        TEAM_ELIMINATE,
        SPECTATOR_JOIN,
        SPECTATOR_LEAVE,
        MATCH_END
    }

    // ---------------------------------------------------------------------------
    // Factory methods — each call site stays readable without long null chains
    // ---------------------------------------------------------------------------

    public static MatchEvent matchStart(long timestamp) {
        return new MatchEvent(EventType.MATCH_START, timestamp,
                null, null, null, null, null, null, null, false, null, false);
    }

    public static MatchEvent matchEnd(long timestamp) {
        return new MatchEvent(EventType.MATCH_END, timestamp,
                null, null, null, null, null, null, null, false, null, false);
    }

    public static MatchEvent playerJoin(long timestamp, String uuid, String name, String team) {
        return new MatchEvent(EventType.PLAYER_JOIN, timestamp,
                uuid, name, team, null, null, null, null, false, null, false);
    }

    public static MatchEvent playerLeave(long timestamp, String uuid, String name, String team, boolean wasSpectating) {
        return new MatchEvent(EventType.PLAYER_LEAVE, timestamp,
                uuid, name, team, null, null, null, null, false, null, wasSpectating);
    }

    public static MatchEvent playerDeath(long timestamp, String uuid, String name, String team, boolean fatal,
                                         String deathCause) {
        return new MatchEvent(EventType.PLAYER_DEATH, timestamp,
                uuid, name, team, null, null, null, null, fatal, deathCause, false);
    }

    public static MatchEvent playerKill(long timestamp, String killerUuid, String killerName, String killerTeam,
                                        String victimName, boolean finalKill, String deathCause) {
        // victim name stored in playerName for display convenience; killerUuid/Name/Team track the killer
        return new MatchEvent(EventType.PLAYER_KILL, timestamp,
                null, victimName, null, killerUuid, killerName, killerTeam, null, finalKill, deathCause, false);
    }

    public static MatchEvent bedBreak(long timestamp, String breakerUuid, String breakerName, String breakerTeam,
                                      String bedTeam) {
        return new MatchEvent(EventType.BED_BREAK, timestamp,
                breakerUuid, breakerName, breakerTeam, null, null, null, bedTeam, false, null, false);
    }

    public static MatchEvent teamEliminate(long timestamp, String teamName) {
        return new MatchEvent(EventType.TEAM_ELIMINATE, timestamp,
                null, null, teamName, null, null, null, null, false, null, false);
    }

    public static MatchEvent spectatorJoin(long timestamp, String uuid, String name) {
        return new MatchEvent(EventType.SPECTATOR_JOIN, timestamp,
                uuid, name, null, null, null, null, null, false, null, false);
    }

    public static MatchEvent spectatorLeave(long timestamp, String uuid, String name) {
        return new MatchEvent(EventType.SPECTATOR_LEAVE, timestamp,
                uuid, name, null, null, null, null, null, false, null, false);
    }

    // ---------------------------------------------------------------------------
    // Display helpers — used by GUI and export
    // ---------------------------------------------------------------------------

    /** Seconds elapsed since matchStartUnix. May be negative for lobby-phase events. */
    public int offsetSeconds(long matchStartUnix) {
        return (int) (timestamp - matchStartUnix);
    }

    /** "+M:SS" format; negative offsets show as "lobby". */
    public String formatOffset(long matchStartUnix) {
        int off = offsetSeconds(matchStartUnix);
        if (off < 0) return "lobby";
        return String.format("+%d:%02d", off / 60, off % 60);
    }
}
