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
        String killerUuid,  // PLAYER_DEATH: the responsible player MBedwars attributed (null if none); legacy PLAYER_KILL: the killer
        String killerName,
        String killerTeam,
        String bedTeam,     // team whose bed was broken (BED_BREAK only)
        boolean isFinal,    // fatal death / final kill, depending on type
        String deathCause,  // Bukkit EntityDamageEvent.DamageCause name; how the victim actually died (e.g. VOID)
        boolean wasSpectating, // PLAYER_LEAVE only: true if already eliminated (spectating) before they quit
        String killCause    // PLAYER_DEATH with attribution: how the killer contributed (e.g. ENTITY_ATTACK for a punch into the void)
) {

    public enum EventType {
        MATCH_START,
        PLAYER_JOIN,
        PLAYER_LEAVE,
        PLAYER_DEATH,
        /**
         * Legacy (pre-0.7.0) standalone kill row. Since 0.7.0 attribution is merged into the
         * victim's PLAYER_DEATH row; this type is only written as a fallback when a kill can't be
         * matched to a death, and is still fully supported when reading old match documents.
         */
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
                null, null, null, null, null, null, null, false, null, false, null);
    }

    public static MatchEvent matchEnd(long timestamp) {
        return new MatchEvent(EventType.MATCH_END, timestamp,
                null, null, null, null, null, null, null, false, null, false, null);
    }

    public static MatchEvent playerJoin(long timestamp, String uuid, String name, String team) {
        return new MatchEvent(EventType.PLAYER_JOIN, timestamp,
                uuid, name, team, null, null, null, null, false, null, false, null);
    }

    public static MatchEvent playerLeave(long timestamp, String uuid, String name, String team, boolean wasSpectating) {
        return new MatchEvent(EventType.PLAYER_LEAVE, timestamp,
                uuid, name, team, null, null, null, null, false, null, wasSpectating, null);
    }

    /** Death row, optionally carrying the responsible player MBedwars attributed the death to. */
    public static MatchEvent playerDeath(long timestamp, String uuid, String name, String team, boolean fatal,
                                         String deathCause, String killerUuid, String killerName, String killerTeam,
                                         String killCause) {
        return new MatchEvent(EventType.PLAYER_DEATH, timestamp,
                uuid, name, team, killerUuid, killerName, killerTeam, null, fatal, deathCause, false, killCause);
    }

    /** Legacy standalone kill row — only used when a kill can't be matched to its death row. */
    public static MatchEvent playerKill(long timestamp, String killerUuid, String killerName, String killerTeam,
                                        String victimName, boolean finalKill, String deathCause) {
        // victim name stored in playerName for display convenience; killerUuid/Name/Team track the killer
        return new MatchEvent(EventType.PLAYER_KILL, timestamp,
                null, victimName, null, killerUuid, killerName, killerTeam, null, finalKill, deathCause, false, null);
    }

    public static MatchEvent bedBreak(long timestamp, String breakerUuid, String breakerName, String breakerTeam,
                                      String bedTeam) {
        return new MatchEvent(EventType.BED_BREAK, timestamp,
                breakerUuid, breakerName, breakerTeam, null, null, null, bedTeam, false, null, false, null);
    }

    public static MatchEvent teamEliminate(long timestamp, String teamName) {
        return new MatchEvent(EventType.TEAM_ELIMINATE, timestamp,
                null, null, teamName, null, null, null, null, false, null, false, null);
    }

    public static MatchEvent spectatorJoin(long timestamp, String uuid, String name) {
        return new MatchEvent(EventType.SPECTATOR_JOIN, timestamp,
                uuid, name, null, null, null, null, null, false, null, false, null);
    }

    public static MatchEvent spectatorLeave(long timestamp, String uuid, String name) {
        return new MatchEvent(EventType.SPECTATOR_LEAVE, timestamp,
                uuid, name, null, null, null, null, null, false, null, false, null);
    }

    /**
     * Copy of a PLAYER_DEATH row with kill attribution attached. Used when the kill event arrives
     * after its death row was already logged. isFinal is OR-ed: MBedwars flags final on both sides
     * of the same death, but if either side reported it, the merged row must reflect it.
     */
    public MatchEvent withKiller(String killerUuid, String killerName, String killerTeam,
                                 boolean finalKill, String killCause) {
        return new MatchEvent(type, timestamp, playerUuid, playerName, playerTeam,
                killerUuid, killerName, killerTeam, bedTeam, isFinal || finalKill, deathCause,
                wasSpectating, killCause);
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
