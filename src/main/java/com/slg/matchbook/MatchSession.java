package com.slg.matchbook;

import com.slg.matchbook.model.MatchEvent;
import de.marcely.bedwars.api.arena.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /**
     * Matchbook version running when this session was created. Persisted with the match record so
     * exports can attribute data to the build that recorded it (not the build that exported it).
     */
    public final String matchbookVersion;

    /**
     * True once RoundStartEvent has actually fired for this session (not just the early session
     * created on first join). Used to suppress pre-match join/leave events — nobody wants a lobby
     * full of PLAYER_JOIN/PLAYER_LEAVE noise in the match's event log before the game even started.
     */
    public volatile boolean started = false;

    // Thread-safe: mutated from both the main thread (event handlers) and the async MBedwars
    // stats-callback chain that runs finalizePlacements()/getArenaTeamsSafe() at round end.
    public final Set<de.marcely.bedwars.api.arena.Team> bedLostTeams = ConcurrentHashMap.newKeySet();

    // ---------------------------
    // Placement tracking
    // ---------------------------

    /** Teams that participated in this match (derived from player membership). */
    public final Set<Team> participatingTeams = ConcurrentHashMap.newKeySet();

    /**
     * Alive players (by UUID) per team. Players are removed on fatal (final) death.
     * This is only used for placement detection.
     */
    public final ConcurrentMap<Team, Set<UUID>> alivePlayersByTeam = new ConcurrentHashMap<>();

    /** Team -> placement rank (1 = 1st, 2 = 2nd, ...). */
    public final ConcurrentMap<Team, Integer> placementByTeam = new ConcurrentHashMap<>();

    /** Elimination order of teams (first eliminated first). */
    public final java.util.List<Team> eliminationOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Team count frozen once the round-start roster is fully known (see {@link #freezeTotalTeams()}).
     * Prevents an elimination detected before every team has generated a tracking event from computing
     * placement against an undercounted, still-growing {@link #participatingTeams} set.
     */
    private volatile int frozenTotalTeams = 0;

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

    /**
     * Each participant's per-round ("game") stats reading, captured the first moment they became a
     * real participant in THIS match (round-start roster, a late join, or a mid-match team
     * assignment). MBedwars' own game-stats counters are expected to already read zero at that point,
     * but that reset can be timing-dependent (e.g. a player sitting in this same arena's next lobby
     * still holds their previous round's final numbers until their own next RoundStart actually
     * fires). Final matchStats are stored as a diff against this baseline rather than trusted as an
     * already-zeroed absolute value, so this match's recorded stats are always relative to 0 for this
     * player regardless of what MBedwars' counter happened to hold when they joined. Uses
     * putIfAbsent semantics (see putMatchStatsBaselineIfAbsent) so a mid-match rejoin can never reset
     * the baseline captured at the player's true first join.
     */
    private final ConcurrentMap<UUID, StatSnapshot> matchStatsBaseline = new ConcurrentHashMap<>();

    /**
     * Guards the quit-time stats capture against rejoins. The quit-path fallback snapshot arrives on
     * an async stats callback; if the player rejoins before it lands, removeMatchStats() has already
     * run and the stale quit-time snapshot must not be written afterwards — it would freeze the
     * player's stats at quit-time values for the rest of the match. removeMatchStats() bumps the
     * generation; putMatchStatsIfGeneration() only writes if the generation hasn't moved since the
     * quit. Both are synchronized on this lock so check-and-put is atomic against the bump.
     */
    private final Object matchStatsGenLock = new Object();
    private final java.util.Map<UUID, Long> matchStatsGeneration = new java.util.HashMap<>();

    private final ConcurrentMap<UUID, Long> startTakenUnixByPlayer = new ConcurrentHashMap<>();

    // Reliable per-match counters based on MBedwars events.
    // This is used as a backstop for key stats (kills/deaths/bed breaks) and as the persistence trigger.
    private final ConcurrentMap<UUID, ConcurrentMap<String, LongAdder>> triggerIncrements = new ConcurrentHashMap<>();
    private final AtomicBoolean triggerActivity = new AtomicBoolean(false);

    // Event log — every discrete in-match event in chronological order.
    private final List<MatchEvent> events = Collections.synchronizedList(new ArrayList<>());
    // Deduplicates join events when both PlayerJoinArenaEvent and SpectatorJoinArenaEvent fire.
    private final Set<UUID> joinEventLogged = ConcurrentHashMap.newKeySet();

    /**
     * Max seconds between a death row and its kill event for them to be considered the same death.
     * Only consulted on the fallback pairing path (no DeathKey available) and as a sanity bound on
     * identity-keyed amends; the primary pairing is exact via {@link DeathKey}.
     */
    private static final long KILL_MERGE_WINDOW_SECONDS = 10L;

    /**
     * Identity key linking the two MBedwars events fired for one death. PlayerKillPlayerEvent
     * extends PlayerIngameDeathEvent and both wrap the same underlying Bukkit PlayerDeathEvent,
     * so the victim plus that object's identity hash pins a kill event to the exact death it
     * describes — no time-window guessing, immune to fast-respawn double deaths and to a second
     * kill overwriting an unmatched first one.
     */
    public record DeathKey(UUID victim, int bukkitEventIdentity) {}

    /** Kill attribution that arrived before its matching death row (see attributeKill). */
    public record PendingKill(long timestamp, String killerUuid, String killerName, String killerTeam,
                              boolean finalKill, String killCause, String victimName) {}

    private final ConcurrentMap<UUID, PendingKill> pendingKills = new ConcurrentHashMap<>();

    /** Unattributed PLAYER_DEATH rows by death key, awaiting a possible kill-event amend. */
    private final ConcurrentMap<DeathKey, MatchEvent> deathRowsByKey = new ConcurrentHashMap<>();

    /** Deaths whose full row was already written by the kill event (kill arrived first). */
    private final Set<DeathKey> killLoggedDeaths = ConcurrentHashMap.newKeySet();

    /** Trips once if identity pairing misses and the victim+time fallback catches it. */
    private final AtomicBoolean identityPairMissWarned = new AtomicBoolean(false);

    public MatchSession(String matchId, String arenaName, long startUnix, String matchbookVersion) {
        this.matchId = matchId;
        this.arenaName = arenaName;
        this.startUnix = startUnix;
        this.matchbookVersion = matchbookVersion;
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
        if (uuid == null) return;
        synchronized (matchStatsGenLock) {
            matchStatsGeneration.merge(uuid, 1L, Long::sum);
            matchStats.remove(uuid);
        }
    }

    /** Current invalidation generation for a player's captured match stats (see matchStatsGenLock). */
    public long getMatchStatsGeneration(UUID uuid) {
        if (uuid == null) return 0L;
        synchronized (matchStatsGenLock) {
            return matchStatsGeneration.getOrDefault(uuid, 0L);
        }
    }

    /** Writes a captured snapshot only if the player's stats weren't invalidated (rejoin) since {@code expectedGeneration} was read. */
    public void putMatchStatsIfGeneration(UUID uuid, StatSnapshot snap, long expectedGeneration) {
        if (uuid == null || snap == null) return;
        synchronized (matchStatsGenLock) {
            if (matchStatsGeneration.getOrDefault(uuid, 0L) == expectedGeneration) {
                matchStats.put(uuid, snap);
            }
        }
    }

    public StatSnapshot getMatchStats(UUID uuid) {
        return matchStats.get(uuid);
    }

    /** Records a player's game-stats baseline once — the first call for a given uuid wins (see field javadoc). */
    public void putMatchStatsBaselineIfAbsent(UUID uuid, StatSnapshot snap) {
        if (uuid == null || snap == null) return;
        matchStatsBaseline.putIfAbsent(uuid, snap);
    }

    public StatSnapshot getMatchStatsBaseline(UUID uuid) {
        return matchStatsBaseline.get(uuid);
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

    /** True if this player is currently tracked as alive on the given team. */
    public boolean isPlayerAlive(Team team, UUID uuid) {
        if (team == null || uuid == null) return false;
        Set<UUID> alive = alivePlayersByTeam.get(team);
        return alive != null && alive.contains(uuid);
    }

    /**
     * Freezes the total-team count at the number of teams observed on the round-start roster.
     * Should be called once that roster has been fully processed, so that placement math has a
     * stable denominator even if {@link #participatingTeams} is still being populated by
     * late-arriving join/assignment events.
     *
     * Takes the observed count explicitly rather than reading {@link #participatingTeams}: that set
     * accumulates every team any player was EVER on, including lobby-only teams a player picked in
     * the team selector and then switched away from before the round started. Such a team has no
     * players when the match actually plays out, but counting it inflates the placement denominator
     * and makes every elimination worth one place too little (a 3-team match recording 1st/3rd/4th
     * with the phantom team silently holding 2nd).
     */
    public void freezeTotalTeams(int observedTeamCount) {
        frozenTotalTeams = Math.max(frozenTotalTeams, observedTeamCount);
    }

    /**
     * Removes a team from every piece of placement tracking.
     *
     * Used at round end to discard teams that nobody actually finished the match on (see
     * {@link #freezeTotalTeams(int)}); such a team would otherwise consume a placement rank that no
     * player owns, leaving a hole in the recorded standings.
     */
    public void dropTeam(Team team) {
        if (team == null) return;
        participatingTeams.remove(team);
        alivePlayersByTeam.remove(team);
        placementByTeam.remove(team);
        bedLostTeams.remove(team);
        synchronized (eliminationOrder) {
            eliminationOrder.remove(team);
        }
    }

    /**
     * Renumbers placements into a contiguous 1..N competition ranking, preserving the order teams
     * finished in.
     *
     * Live elimination tracking derives a place arithmetically from the frozen team count
     * ({@code totalTeams - eliminationIndex + 1}), so any drift between that count and the number of
     * teams that actually finish shows up as a gap or a collision in the final standings. This is the
     * backstop that makes the recorded ranks correct regardless: whatever the raw numbers, the
     * relative order they express is what gets persisted.
     *
     * Ordering: by assigned place, then — for teams that collided on a place because the denominator
     * was too small — by elimination order, with later eliminations ranking higher and never-eliminated
     * teams ranking above every eliminated one. Genuine ties (teams that were never eliminated and hold
     * the same place, e.g. a time-limit draw) keep a shared rank and the next rank skips accordingly,
     * i.e. standard competition ranking: 1, 1, 3.
     */
    public void normalizePlacements() {
        if (placementByTeam.isEmpty()) return;

        // Team -> 1-based elimination index; absent means "never eliminated".
        java.util.Map<Team, Integer> eliminatedAt = new java.util.HashMap<>();
        synchronized (eliminationOrder) {
            for (int i = 0; i < eliminationOrder.size(); i++) {
                Team t = eliminationOrder.get(i);
                if (t != null) eliminatedAt.putIfAbsent(t, i + 1);
            }
        }

        // Higher survival score = finished better. Never eliminated outranks every elimination.
        java.util.function.ToIntFunction<Team> survival =
                t -> eliminatedAt.getOrDefault(t, Integer.MAX_VALUE);

        List<Team> ranked = new ArrayList<>(placementByTeam.keySet());
        ranked.sort(java.util.Comparator
                .comparingInt((Team t) -> placementByTeam.getOrDefault(t, Integer.MAX_VALUE))
                .thenComparing(survival::applyAsInt, java.util.Comparator.reverseOrder()));

        int rank = 0;
        int position = 0;
        Integer prevPlace = null;
        Integer prevSurvival = null;

        for (Team t : ranked) {
            int place = placementByTeam.getOrDefault(t, Integer.MAX_VALUE);
            int surv = survival.applyAsInt(t);
            position++;

            // Only teams that are genuinely indistinguishable (same place, same elimination status)
            // share a rank; everything else advances to its own.
            if (prevPlace == null || place != prevPlace || surv != prevSurvival) {
                rank = position;
            }

            placementByTeam.put(t, rank);
            prevPlace = place;
            prevSurvival = surv;
        }
    }

    /**
     * Placement denominator: the number of teams actually playing when the round started, never
     * the arena's full configured team roster (which may include unused/empty slots) and never
     * inflated by teams that only pick up a player LATER (e.g. an admin reassignment/auto-balance
     * after round start). Once frozen, this is fixed for the rest of the match — a team gaining a
     * player post-round-start must not change what place earlier eliminations are worth.
     */
    public int totalTeams() {
        return frozenTotalTeams > 0 ? frozenTotalTeams : participatingTeams.size();
    }

    // ---------------------------------------------------------------------------
    // Event log
    // ---------------------------------------------------------------------------

    public void addEvent(MatchEvent event) {
        if (event != null) events.add(event);
    }

    /** Returns a snapshot of the event list safe to iterate off-thread. */
    public List<MatchEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** Registers a just-logged unattributed death row so a later kill event can amend it exactly. */
    public void registerDeathRow(DeathKey key, MatchEvent row) {
        if (key == null || row == null) return;
        deathRowsByKey.put(key, row);
    }

    /** Marks a death whose full row was written by the kill event, so the death event skips its own. */
    public void markKillLoggedDeath(DeathKey key) {
        if (key != null) killLoggedDeaths.add(key);
    }

    /** True (once) if the kill event already wrote this death's row. */
    public boolean consumeKillLoggedDeath(DeathKey key) {
        return key != null && killLoggedDeaths.remove(key);
    }

    /**
     * Merges a kill attribution into the exact death row it belongs to, located via the shared
     * Bukkit PlayerDeathEvent identity (see DeathKey). Falls back to the victim+time scan if the
     * identity lookup misses — that fallback firing means the two MBedwars events did not wrap the
     * same Bukkit event, which is worth a (one-time) warning since exact pairing depends on it.
     *
     * @return true if a death row was amended; false means no row exists yet for this death and
     *         the caller should write the full row itself.
     */
    public boolean attributeKillByKey(DeathKey key, PendingKill kill) {
        if (key == null || kill == null) return false;
        MatchEvent row = deathRowsByKey.remove(key);
        if (row != null && kill.timestamp() - row.timestamp() <= KILL_MERGE_WINDOW_SECONDS) {
            synchronized (events) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    if (events.get(i) == row) {
                        events.set(i, row.withKiller(kill.killerUuid(), kill.killerName(),
                                kill.killerTeam(), kill.finalKill(), kill.killCause()));
                        return true;
                    }
                }
            }
        }
        if (amendRecentDeathRow(key.victim(), kill)) {
            if (identityPairMissWarned.compareAndSet(false, true)) {
                org.bukkit.Bukkit.getLogger().warning("Matchbook: kill/death identity pairing missed in match "
                        + matchId + "; matched by victim+time fallback instead. Attribution is still recorded,"
                        + " but please report this — it means MBedwars fired the two death events around"
                        + " different Bukkit events.");
            }
            return true;
        }
        return false;
    }

    /**
     * Records who MBedwars attributed a death to when no DeathKey is available (no victim handle
     * or no wrapped Bukkit event) — legacy victim+time pairing:
     *  - if the victim's death row is already logged (and unattributed, within the merge window),
     *    it is amended in place — one row per death, killer columns filled in;
     *  - otherwise the attribution is parked and consumed when the death row gets logged
     *    (see consumePendingKill), or flushed as a legacy PLAYER_KILL row at save time if no
     *    death ever matches (flushPendingKills) so the kill is never silently dropped.
     */
    public void attributeKill(UUID victimUuid, PendingKill kill) {
        if (victimUuid == null || kill == null) return;
        if (amendRecentDeathRow(victimUuid, kill)) return;
        pendingKills.put(victimUuid, kill);
    }

    /** Amends the victim's most recent unattributed death row within the merge window, if any. */
    private boolean amendRecentDeathRow(UUID victimUuid, PendingKill kill) {
        String victimUuidStr = victimUuid.toString();
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                MatchEvent ev = events.get(i);
                if (kill.timestamp() - ev.timestamp() > KILL_MERGE_WINDOW_SECONDS) break;
                if (ev.type() != MatchEvent.EventType.PLAYER_DEATH) continue;
                if (!victimUuidStr.equals(ev.playerUuid())) continue;
                if (ev.killerUuid() != null || ev.killerName() != null) continue; // already attributed
                events.set(i, ev.withKiller(kill.killerUuid(), kill.killerName(), kill.killerTeam(),
                        kill.finalKill(), kill.killCause()));
                return true;
            }
        }
        return false;
    }

    /** Takes a parked kill attribution for this victim if one exists within the merge window. */
    public PendingKill consumePendingKill(UUID victimUuid, long deathTimestamp) {
        if (victimUuid == null) return null;
        PendingKill kill = pendingKills.remove(victimUuid);
        if (kill == null) return null;
        if (Math.abs(deathTimestamp - kill.timestamp()) > KILL_MERGE_WINDOW_SECONDS) return null;
        return kill;
    }

    /**
     * Converts any never-matched kill attributions into legacy PLAYER_KILL rows (inserted in
     * timestamp order) so the kill survives in the record. Called at save time; idempotent.
     */
    public void flushPendingKills() {
        int flushed = 0;
        for (var entry : pendingKills.entrySet()) {
            PendingKill k = entry.getValue();
            if (pendingKills.remove(entry.getKey(), k)) {
                MatchEvent ev = MatchEvent.playerKill(k.timestamp(), k.killerUuid(), k.killerName(),
                        k.killerTeam(), k.victimName(), k.finalKill(), k.killCause());
                synchronized (events) {
                    int at = events.size();
                    while (at > 0 && events.get(at - 1).timestamp() > ev.timestamp()) at--;
                    events.add(at, ev);
                }
                flushed++;
            }
        }
        // With identity pairing, kills carrying a DeathKey never park here — a flush now means a
        // death event genuinely never arrived, which is a capture gap worth surfacing.
        if (flushed > 0) {
            org.bukkit.Bukkit.getLogger().warning("Matchbook: " + flushed + " kill(s) in match " + matchId
                    + " never matched a death event; recorded as standalone PLAYER_KILL rows.");
        }
    }

    /**
     * Returns true the first time this uuid is logged as joining, false on subsequent calls.
     * Used to prevent double-logging when both PlayerJoinArenaEvent and SpectatorJoinArenaEvent fire.
     */
    public boolean tryLogJoin(UUID uuid) {
        return uuid != null && joinEventLogged.add(uuid);
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
     * Teams tied for 1st (more than one team holds placement 1) are NOT given matchbook:1st_place —
     * a tie is not a win. Those players get matchbook:ties instead, mirroring how MatchDocument
     * marks a whole-match tie.
     *
     * This makes CSV multi-export aggregation "just work" by summation.
     */
    public void applyPlacementsToMatchStats() {
        if (placementByTeam.isEmpty()) return;

        long firstPlaceTeams = placementByTeam.values().stream().filter(p -> p != null && p == 1).count();
        boolean tiedForFirst = firstPlaceTeams > 1;

        for (UUID uuid : participants) {
            Team t = teamByPlayer.get(uuid);
            if (t == null) continue;
            Integer place = placementByTeam.get(t);
            if (place == null || place <= 0) continue;

            String key = (place == 1 && tiedForFirst) ? "matchbook:ties" : "matchbook:" + ordinal(place) + "_place";
            StatSnapshot existing = matchStats.get(uuid);
            var out = existing != null ? new java.util.LinkedHashMap<>(existing.values()) : new java.util.LinkedHashMap<String, Long>();
            out.put(key, out.getOrDefault(key, 0L) + 1L);
            matchStats.put(uuid, new StatSnapshot(out));
        }
    }

    /**
     * Names of every team holding 1st place — exactly one for an outright win, several for a tie,
     * none if placements were never finalized (an aborted match).
     */
    public List<String> getFirstPlaceTeamNames() {
        List<String> out = new ArrayList<>();
        for (var e : placementByTeam.entrySet()) {
            if (e.getValue() != null && e.getValue() == 1 && e.getKey() != null) out.add(e.getKey().name());
        }
        return out;
    }

    /** Team names currently tied for 1st place (empty unless more than one team holds placement 1). */
    public List<String> getTiedTeamNames() {
        List<String> first = getFirstPlaceTeamNames();
        return first.size() > 1 ? first : List.of();
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
