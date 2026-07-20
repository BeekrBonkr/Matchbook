package com.slg.matchbook.service;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.StatSnapshot;
import com.slg.matchbook.io.MatchYamlCodec;
import com.slg.matchbook.model.MatchDocument;
import com.slg.matchbook.model.MatchEvent;
import com.slg.matchbook.util.MatchIdUtil;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.QuitPlayerMemory;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.player.PlayerDataAPI;
import de.marcely.bedwars.api.player.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns MatchSession lifecycle.
 *
 * Key design constraints:
 *  - Players can leave a running match and join a new one before the first match ends.
 *  - Matchbook must NOT attribute stats from the new match to the old match.
 *
 * Approach:
 *  - Prefer MBedwars *per-round* ("game") stats stored on QuitPlayerMemory / PlayerStats#getGameStats.
 *  - Additionally, capture critical stats (kills/deaths/bed breaks) directly from MBedwars events.
 *
 * This solves:
 *  - Missing death counts
 *  - Cross-match contamination
 *  - "Left a running match and now can't join new ones" (rejoin-memory)
 */
public final class MatchLifecycleService {

    /**
     * These keys are what we consider "real match activity".
     * Matches are only persisted if at least one of these is non-zero.
     */
    private static final Set<String> PERSIST_TRIGGER_KEYS = Set.of(
            "bedwars:kills",
            "bedwars:final_kills",
            "bedwars:deaths",
            "bedwars:final_deaths",
            "bedwars:beds_destroyed"
    );

    private final MatchbookPlugin plugin;
    private final PlayerDataAPI playerDataApi;

    private final ConcurrentMap<String, MatchSession> sessionsByArena = new ConcurrentHashMap<>();

    /**
     * Scoreboards often continue to request placeholders briefly after RoundEnd or while players are
     * transitioning out of an arena. Keep a short "last known" cache so %matchbook_matchcode% stays reliable.
     */
    private final ConcurrentMap<UUID, CachedMatchId> lastMatchIdByPlayer = new ConcurrentHashMap<>();

    private record CachedMatchId(String matchId, String arenaName, long expiresAtMillis) {}

    /** Arena names we've already logged a "not locally hosted" warning for, to avoid log spam. */
    private final Set<String> warnedNonLocalArenas = ConcurrentHashMap.newKeySet();

    public MatchLifecycleService(MatchbookPlugin plugin) {
        this.plugin = plugin;
        // MBedwars API is accessed via static entrypoints (no singleton instance).
        this.playerDataApi = BedwarsAPI.getPlayerDataAPI();
    }

    public MatchSession getSession(String arenaName) {
        return sessionsByArena.get(arenaName);
    }

    /**
     * True if this arena is actually hosted (has a loaded game world) on this server instance.
     *
     * MBedwars supports network-wide arena awareness (RemoteArena/RemoteAPI) so that hub servers
     * behind a proxy can see arenas hosted on other backend servers. A loaded game world can only
     * exist on the server actually running the match, so this rejects any arena reference that
     * isn't truly local. Without this, a hub server with zero arenas of its own could still create
     * and persist bogus match sessions for arenas it merely knows about over the network, which
     * then never receive a real RoundEnd and get stuck until the server restarts.
     */
    private boolean isLocallyHosted(Arena arena) {
        if (arena == null) return false;
        try {
            if (arena.getGameWorld() != null) return true;
        } catch (Throwable ignored) {
        }
        if (warnedNonLocalArenas.add(arena.getName())) {
            plugin.getLogger().warning("Matchbook: ignoring arena '" + arena.getName()
                    + "' — no game world loaded on this server. This usually means MBedwars is "
                    + "reporting a remote/proxied arena that isn't actually hosted here.");
        }
        return false;
    }

    /**
     * Ensure a session exists for an arena.
     *
     * This is intentionally safe to call from PlaceholderAPI resolution: it does not start snapshots,
     * it simply guarantees a stable matchId for the arena instance.
     */
    public MatchSession getOrCreateSession(Arena arena, String reason) {
        if (arena == null || !isLocallyHosted(arena)) return null;

        MatchSession session = liveSessionOrNew(arena.getName());

        // If this call originated from a player context, we still want placeholders to be stable
        // while the player is transitioning.
        if (reason != null && !reason.isBlank()) {
            // no-op: reason is for future debugging hooks
        }

        return session;
    }

    public String getCachedMatchId(UUID playerUuid) {
        if (playerUuid == null) return "";
        CachedMatchId c = lastMatchIdByPlayer.get(playerUuid);
        if (c == null) return "";
        if (System.currentTimeMillis() > c.expiresAtMillis) {
            lastMatchIdByPlayer.remove(playerUuid);
            return "";
        }
        return c.matchId;
    }

    public void cacheMatchId(UUID playerUuid, String arenaName, String matchId) {
        if (playerUuid == null || matchId == null || matchId.isBlank()) return;
        long graceSeconds = plugin.getMatchbookConfig().raw().getLong("placeholder.grace_seconds", 60L);
        long now = System.currentTimeMillis();
        long expires = now + Math.max(5L, graceSeconds) * 1000L;
        lastMatchIdByPlayer.put(playerUuid, new CachedMatchId(matchId, arenaName, expires));

        // Entries are otherwise only evicted on read, so players who never resolve a placeholder
        // again would accumulate forever. Purge expired entries once the map grows noticeable.
        if (lastMatchIdByPlayer.size() > 512) {
            lastMatchIdByPlayer.values().removeIf(c -> now > c.expiresAtMillis());
        }
    }

    /**
     * Returns the arena's current session, creating one if absent — but NEVER an already-ended one.
     *
     * A session with endUnix set belongs to a finished round whose delayed save chain hasn't removed
     * it from the map yet (end-snapshot delay + stats callbacks can take seconds under load). If the
     * arena starts its next round inside that window, reusing the ended session would merge the two
     * rounds into one match document: the new round inherits the old matchId, its players leak into
     * the old match's record, and the old round's finalize then removes the session out from under
     * the new round, silently dropping the rest of its events. Ended sessions are evicted here and a
     * fresh one is created instead; the finalize chain keeps its own direct reference, so its save is
     * unaffected.
     */
    private MatchSession liveSessionOrNew(String arenaName) {
        while (true) {
            MatchSession session = sessionsByArena.computeIfAbsent(arenaName, __ ->
                    new MatchSession(MatchIdUtil.newMatchId(), arenaName, System.currentTimeMillis() / 1000L,
                            plugin.getDescription().getVersion()));
            if (session.endUnix == null) return session;
            sessionsByArena.remove(arenaName, session);
        }
    }

    public void onRoundStart(Arena arena) {
        if (arena == null || !isLocallyHosted(arena)) return;

        // Reuse the pre-round session (created early on first join) so its matchId stays stable,
        // but never an ended one still awaiting its save chain.
        MatchSession session = liveSessionOrNew(arena.getName());

        // Overwrite start time at the true round start.
        session.startUnix = System.currentTimeMillis() / 1000L;

        session.addEvent(MatchEvent.matchStart(session.startUnix));
        session.started = true;

        // Take totals snapshot near start for auditing/debugging.
        takeStartSnapshots(arena, session);

        // Watchdog in case MBedwars doesn't fire RoundEnd.
        startAbortWatchdog(arena, session);

        // Capture the round-start roster (who's on which team) after a short delay, same as
        // classifyArenaJoin(). MBedwars can still be settling team assignments for a few ticks right
        // as RoundStartEvent fires, so reading arena.getPlayerTeam() immediately can undercount teams
        // — which then permanently undercounts totalTeams() below (it's frozen once) and can leave
        // some players' teams unrecorded entirely.
        MatchSession finalSession = session;
        long delay = plugin.getMatchbookConfig().runtimeSettings().joinClassifyDelayTicks();
        Runnable captureRoster = () -> captureRoundStartRoster(arena, finalSession);
        if (delay <= 0L) {
            captureRoster.run();
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, captureRoster, delay);
        }
    }

    private void captureRoundStartRoster(Arena arena, MatchSession session) {
        for (Player p : arena.getPlayers()) {
            session.unmarkSpectatorOnly(p.getUniqueId());
            session.addParticipant(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
            Team team = resolveTeamFromArena(arena, p.getUniqueId());
            session.setTeam(p.getUniqueId(), team);
            if (team != null) {
                session.markTeamParticipating(team);
                session.markPlayerAlive(team, p.getUniqueId());
            }

            cacheMatchId(p.getUniqueId(), arena.getName(), session.matchId);
        }

        // Every team's roster should be known by now; freeze totalTeams() so that a very early
        // elimination can't compute placement against a still-growing, undercounted team set.
        session.freezeTotalTeams();
    }

    public void onPlayerJoinArena(Arena arena, Player player) {
        if (arena == null || player == null || !isLocallyHosted(arena)) return;

        // Create a session early so placeholders can resolve during pre-round phases.
        MatchSession session = liveSessionOrNew(arena.getName());

        UUID uuid = player.getUniqueId();
        cacheMatchId(uuid, arena.getName(), session.matchId);

        // MBedwars can briefly report stale/incomplete team and spectator state right when a
        // player joins — most noticeably in the seconds right after a round starts, where a
        // genuine spectator can transiently read back a leftover/default team (getting wrongly
        // counted as a participant) or a real player can transiently read back no team at all.
        // Wait a moment so MBedwars settles before we classify them.
        long delay = plugin.getMatchbookConfig().runtimeSettings().joinClassifyDelayTicks();
        if (delay <= 0L) {
            classifyArenaJoin(arena, player, session);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isStillInArena(arena, player)) return;
            classifyArenaJoin(arena, player, session);
        }, delay);
    }

    private boolean isStillInArena(Arena arena, Player player) {
        try {
            return arena.getPlayers().contains(player);
        } catch (Throwable ignored) {
            // Fail open: better to classify a player than to silently drop a real participant
            // because of an unrelated reflection/API hiccup.
            return true;
        }
    }

    private void classifyArenaJoin(Arena arena, Player player, MatchSession session) {
        UUID uuid = player.getUniqueId();

        // Determine current role
        Team team = resolveTeamFromArena(arena, uuid);
        boolean spectator = isSpectator(arena, player);

        // Spectator-only viewers can join an arena mid-match to watch.
        // They must NOT be counted as participants/stat owners.
        if (spectator && team == null && session.getTeam(uuid) == null) {
            session.unmarkPending(uuid);
            session.markSpectatorOnly(uuid);
            session.setUsername(uuid, player.getName());
            if (session.started && session.tryLogJoin(uuid)) {
                session.addEvent(MatchEvent.spectatorJoin(now(), player.getUniqueId().toString(), player.getName()));
            }
            cacheMatchId(uuid, arena.getName(), session.matchId);
            return;
        }

        // If MBedwars hasn't assigned a team yet (common in lobby), keep them pending.
        // They will be promoted to a real participant the moment they get a team.
        if (team == null && !spectator && session.getTeam(uuid) == null) {
            session.unmarkSpectatorOnly(uuid);
            session.markPending(uuid);
            session.setUsername(uuid, player.getName());
            if (session.started && session.tryLogJoin(uuid)) {
                session.addEvent(MatchEvent.playerJoin(now(), player.getUniqueId().toString(), player.getName(), null));
            }
            cacheMatchId(uuid, arena.getName(), session.matchId);
            return;
        }

        // Real participant (team assigned, or already known participant)
        session.promoteToParticipant(uuid, team);
        session.setUsername(uuid, player.getName());
        if (session.started && session.tryLogJoin(uuid)) {
            String teamName = team != null ? team.name() : null;
            session.addEvent(MatchEvent.playerJoin(now(), player.getUniqueId().toString(), player.getName(), teamName));
        }

        // Placement tracking: mark participation + alive status for this player.
        // Don't resurrect a player who is currently an in-game spectator (e.g. reconnecting after
        // an earlier fatal death) — MBedwars' own spectator classification is the authoritative
        // signal that they are not actually back in the fight, and re-adding them to the alive set
        // would let a genuinely eliminated team dodge/delay elimination detection indefinitely.
        if (team != null) {
            session.markTeamParticipating(team);
            if (!spectator) {
                session.markPlayerAlive(team, uuid);
            }
        }

        // If the player previously quit this same arena (and we captured quit stats),
        // but they rejoined, drop that captured snapshot so we store the final result.
        session.removeMatchStats(uuid);

        cacheMatchId(uuid, arena.getName(), session.matchId);
    }

    public void onArenaWinningTeam(Arena arena, Team winningTeam) {
        if (arena == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        session.winningTeam = winningTeam;
        if (winningTeam == null) {
            session.result = "TIE";
        } else {
            session.result = "WIN:" + winningTeam.name();
        }
    }

    /**
     * Called when MBedwars reports a player quitting an arena.
     * We capture per-round (game) stats for that player from QuitPlayerMemory so later matches
     * don't contaminate the old match.
     */
    public void onPlayerQuitArena(Arena arena, Player player, KickReason reason) {
        if (arena == null || player == null) return;

        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = player.getUniqueId();
        Team team = resolveTeamFromArena(arena, uuid);

        boolean spectator = isSpectator(arena, player);

        // Spectator-only viewers (including lobby->leave->spectate later) should not be captured.
        // If they never had a team in this match, treat them as spectator-only and skip stat capture.
        if (team == null && session.getTeam(uuid) == null && (session.isSpectatorOnly(uuid) || session.isPending(uuid) || spectator)) {
            session.markSpectatorOnly(uuid);
            session.unmarkPending(uuid);
            session.setUsername(uuid, player.getName());
            if (session.started) {
                session.addEvent(MatchEvent.spectatorLeave(now(), player.getUniqueId().toString(), player.getName()));
            }
            return;
        }

        // Otherwise, ensure they are treated as a real participant.
        session.promoteToParticipant(uuid, team);
        session.setUsername(uuid, player.getName());
        Team effectiveTeam = team != null ? team : session.getTeam(uuid);
        String leavingTeam = effectiveTeam != null ? effectiveTeam.name() : null;

        // Was this player already eliminated (in-game spectator on their now-dead team) before this
        // quit, or were they still an active alive player? Must be read BEFORE markPlayerFinalDead
        // below, which would otherwise make every leaving player look like they "were spectating".
        boolean wasSpectating = effectiveTeam != null && !session.isPlayerAlive(effectiveTeam, uuid);
        if (session.started) {
            session.addEvent(MatchEvent.playerLeave(now(), player.getUniqueId().toString(), player.getName(),
                    leavingTeam, wasSpectating));
        }

        // If they quit mid-match, treat as no longer alive for placement purposes.
        // (This matches esports expectations: leaving = eliminated / not alive.)
        // If their bed is already gone, this may be the team's last alive player, so check for
        // elimination live rather than leaving it to the round-end backstop sweep (which can only
        // append it in arbitrary order relative to other backstop-caught teams).
        if (effectiveTeam != null) {
            session.markPlayerFinalDead(effectiveTeam, uuid);
            maybeMarkTeamEliminated(arena, session, effectiveTeam);
        }

        // Capture game stats snapshot from QuitPlayerMemory (best), fallback to live game stats.
        QuitPlayerMemory mem = null;
        try {
            mem = arena.getQuitPlayerMemory(uuid);
        } catch (Throwable ignored) {
        }

        if (mem != null) {
            session.putMatchStats(uuid, snapshotFromGameStatsMap(mem.getGameStats()));

            // Prevent MBedwars rejoin-memory from blocking players joining other arenas.
            if (shouldDisableRejoin(reason)) {
                try {
                    mem.setRejoinPermitted(false);
                } catch (Throwable ignored) {
                }
            }
        } else {
            // Fallback: try to read live game stats right now (safe at quit-time). The callback can
            // arrive on an async thread AFTER this player has already rejoined (which invalidates
            // quit-time stats via removeMatchStats); guard with the generation so a stale snapshot
            // can't resurrect and freeze their stats at quit-time values.
            long gen = session.getMatchStatsGeneration(uuid);
            snapshotGameTrackedStats(uuid, snap -> session.putMatchStatsIfGeneration(uuid, snap, gen));
        }
    }

    public void onRoundEnd(Arena arena) {
        if (arena == null) return;

        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        // Duplicate RoundEnd (MBedwars can re-fire it under forced-stop conditions): the first one
        // already scheduled the finalize chain. Running it twice would save the match twice and
        // stamp every player's matchbook:*_place counter twice.
        if (session.endUnix != null) return;

        session.endUnix = System.currentTimeMillis() / 1000L;

        // Add any remaining online players (still in arena)
        for (Player p : arena.getPlayers()) {
            session.addParticipant(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
            session.setTeam(p.getUniqueId(), resolveTeamFromArena(arena, p.getUniqueId()));
        }

        long delay = plugin.getMatchbookConfig().runtimeSettings().endSnapshotDelayTicks();

        // Capture totals end snapshot (debug/audit), then capture per-match stats and save.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            takeEndSnapshots(arena, session, () -> {
                captureMatchStatsFromArena(arena, session, () -> {
                    // Ensure critical counters are present even if snapshot sources missed them.
                    session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);

                    // Finalize placement keys (elimination order, ties-by-alive-teams, winner => 1st).
                    finalizePlacements(arena, session);

                    // If MBedwars didn't provide a winner (or we accidentally marked a tie),
                    // infer from recorded per-match stats (bedwars:wins). This can correct
                    // finalizePlacements' tie-for-1st into a definitive win (or vice versa), so it
                    // MUST run before placements are baked into matchStats below.
                    inferResultFromRecordedWinStats(session);

                    // Now that placements are definitive, write matchbook:*_place / matchbook:ties.
                    session.applyPlacementsToMatchStats();

                    // Compute the final match result AFTER tie detection/placement finalization.
                    String result = computeResult(session);
                    finishMatch(arena, session, result);
                });
            });
        }, Math.max(1L, delay));
    }

    // ----------------------------------------------------------------------
    // Placement tracking
    // ----------------------------------------------------------------------

    private void maybeMarkTeamEliminated(Arena arena, MatchSession session, Team team) {
        if (arena == null || session == null || team == null) return;
        if (!session.bedLostTeams.contains(team)) return; // bed must be gone
        if (!session.isTeamFullyDead(team)) return;        // all players must be dead

        // Already placed?
        if (session.placementByTeam.containsKey(team)) return;

        // Record elimination order
        session.eliminationOrder.add(team);

        int totalTeams = session.totalTeams();
        // If we don't know total teams yet, approximate from currently tracked teams.
        if (totalTeams <= 0) totalTeams = Math.max(1, session.participatingTeams.size());

        int eliminatedIndex = session.eliminationOrder.size(); // 1-based
        // Never clamp an eliminated team up to 1st: if more teams get eliminated than the frozen
        // round-start count (a team formed mid-match via reassignment/auto-balance), the raw
        // formula goes to 1 or below — which would falsely tie the last-eliminated team with the
        // real winner and downgrade the winner's 1st_place credit into a "tie". An eliminated team
        // can never have won, so floor at 2nd (multiple overflow teams may share it; better a
        // duplicate 2nd than a stolen 1st).
        int place = Math.max(2, totalTeams - eliminatedIndex + 1);
        session.setPlacementIfAbsent(team, place);
    }

    private void finalizePlacements(Arena arena, MatchSession session) {
        if (arena == null || session == null) return;

        boolean tieByAliveTeams = false;

        // If bed break events couldn't identify the bed team (API differences), try to infer
        // bed state directly from Team methods at the end.
        for (Team t : getArenaTeamsSafe(arena, session)) {
            if (t == null) continue;
            if (!session.bedLostTeams.contains(t) && isTeamBedGone(t)) {
                session.bedLostTeams.add(t);
            }
        }

        // Make sure we have a team roster based on participants we've seen.
        for (UUID u : session.getParticipants()) {
            Team t = session.getTeam(u);
            if (t != null) session.markTeamParticipating(t);
        }

        // Detect tie by observing >1 teams still alive at game-over.
        // This can happen when the match ends via time limit / forced end.
        // In that case, ALL alive teams should be considered "1st place".
        //
        // A team that was already definitively eliminated (has a recorded placement from live
        // elimination tracking) must NEVER be reconsidered here. isTeamAliveAtEnd() falls back to
        // reflection across MBedwars Team methods when our own alivePlayersByTeam tracking has no
        // entry for that team (e.g. it never got a markPlayerAlive() call), and that fallback can
        // misreport an already-eliminated team as still alive. Without this guard, such a team's
        // real placement (e.g. 3rd/4th) gets clobbered with a false tie-for-1st — throwing out a
        // correct placement for a team that never actually tied for anything.
        List<Team> aliveAtEnd = new ArrayList<>();
        for (Team t : session.participatingTeams) {
            if (t == null) continue;
            if (session.placementByTeam.containsKey(t)) continue;
            if (isTeamAliveAtEnd(t, session)) aliveAtEnd.add(t);
        }

        // Only treat "multiple teams alive" as a tie when we do NOT have a definite winning team.
        // MBedwars versions / event ordering can briefly leave players marked as alive on multiple teams.
        // If the arena reports a winner, trust it and avoid falsely marking ties.
        tieByAliveTeams = session.winningTeam == null && aliveAtEnd.size() > 1;
        if (tieByAliveTeams) {
            session.result = "TIE";
            session.winningTeam = null;
            for (Team t : aliveAtEnd) {
                session.setPlacementIfAbsent(t, 1);
            }
        }

        // Winner => 1st place (unless tie was detected above).
        if (session.winningTeam != null) session.setPlacementIfAbsent(session.winningTeam, 1);

        // Fill elimination placements (bed gone + fully dead) for any teams we didn't catch live.
        for (Team t : session.participatingTeams) {
            if (t == null) continue;
            if (session.placementByTeam.containsKey(t)) continue;
            if (session.bedLostTeams.contains(t) && session.isTeamFullyDead(t)) {
                maybeMarkTeamEliminated(arena, session, t);
            }
        }

        // Any team without placement (ties/aborts/API edge cases) gets a best-effort assignment.
        // Track which ranks are already taken so multiple ambiguous teams don't collide on the
        // same fallback number (they'd otherwise all get stamped e.g. "4th place").
        Set<Integer> usedPlacements = new HashSet<>(session.placementByTeam.values());
        int nextFallback = session.totalTeams();

        for (Team t : session.participatingTeams) {
            if (session.placementByTeam.containsKey(t)) continue;

            boolean bedLost = session.bedLostTeams.contains(t);
            boolean fullyDead = session.isTeamFullyDead(t);

            if (!bedLost && !fullyDead) {
                // Still alive at end.
                // If we have a winner, treat remaining alive teams as runner-up (2nd).
                // If no winner OR tie-by-alive-teams, treat as tied 1st.
                int place = (tieByAliveTeams || session.winningTeam == null) ? 1 : 2;
                session.setPlacementIfAbsent(t, place);
                usedPlacements.add(place);
                continue;
            }

            if (bedLost && fullyDead) {
                // Should have been caught, but just in case.
                maybeMarkTeamEliminated(arena, session, t);
                Integer assigned = session.placementByTeam.get(t);
                if (assigned != null) usedPlacements.add(assigned);
                continue;
            }

            // Partial info:
            // - fullyDead but bedLost unknown
            // - bedLost but not fullyDead (shouldn't happen, but API glitches can)
            // Put them in the lowest still-available "last place" slot so we never falsely reward
            // them, while still giving distinct ranks to multiple teams landing in this bucket.
            while (nextFallback >= 1 && usedPlacements.contains(nextFallback)) nextFallback--;
            int fallback = Math.max(1, nextFallback);
            if (session.winningTeam != null && fallback == 1 && usedPlacements.contains(1)) fallback = 2;
            session.setPlacementIfAbsent(t, fallback);
            usedPlacements.add(fallback);
            nextFallback--;
        }
    }

    /**
     * Best-effort result inference.
     *
     * Why this exists:
     *  - Some MBedwars builds fire ArenaWinningTeamDetermineEvent with winningTeam == null
     *    even when the match had a clear winner.
     *  - As a fallback, we infer the winning team by looking at the per-match stats and finding
     *    which team has players with bedwars:wins == 1.
     *
     * Behavior:
     *  - If exactly one team has the highest (and >0) wins total => WIN:<TEAM>
     *  - If multiple teams share the highest wins total (>0) => TIE
     *  - If no wins are present => leave result as-is (UNKNOWN/TIE)
     */
    private void inferResultFromRecordedWinStats(MatchSession session) {
        if (session == null) return;

        // Only infer when MB didn't provide a clear winner OR we currently think it's a tie.
        // (We still allow overriding a false tie.)
        boolean needsInference = session.winningTeam == null || (session.result != null && session.result.equalsIgnoreCase("TIE"));
        if (!needsInference) return;

        // Team -> sum of "wins" across players (for this match)
        Map<Team, Long> winsByTeam = new LinkedHashMap<>();

        for (UUID uuid : session.getParticipants()) {
            if (uuid == null) continue;
            if (session.isSpectatorOnly(uuid) || session.isPending(uuid)) continue;

            Team team = session.getTeam(uuid);
            if (team == null) continue;

            long wins = 0L;

            // Prefer per-match stats (QuitPlayerMemory / game stats)
            StatSnapshot matchSnap = session.getMatchStats(uuid);
            if (matchSnap != null) {
                wins = matchSnap.values().getOrDefault("bedwars:wins", 0L);
            } else {
                // Fallback to start/end diff when matchStats is missing.
                StatSnapshot start = session.getStart(uuid);
                StatSnapshot end = session.getEnd(uuid);
                if (start != null && end != null) {
                    wins = StatSnapshot.diff(start, end).getOrDefault("bedwars:wins", 0L);
                }
            }

            if (wins <= 0L) continue;
            winsByTeam.put(team, winsByTeam.getOrDefault(team, 0L) + wins);
        }

        if (winsByTeam.isEmpty()) return;

        long max = 0L;
        for (long v : winsByTeam.values()) max = Math.max(max, v);
        if (max <= 0L) return;

        List<Team> top = new ArrayList<>();
        for (var e : winsByTeam.entrySet()) {
            if (e.getValue() != null && e.getValue() == max) top.add(e.getKey());
        }

        if (top.isEmpty()) return;

        if (top.size() == 1) {
            Team inferred = top.get(0);
            session.winningTeam = inferred;
            session.result = "WIN:" + inferred.name();

            // Ensure placements are definitive. If we previously marked a tie (multiple teams at 1st),
            // demote non-winner 1st-place teams to 2nd.
            session.setPlacementIfAbsent(inferred, 1);
            for (Team t : new ArrayList<>(session.placementByTeam.keySet())) {
                if (t == null || t.equals(inferred)) continue;
                Integer place = session.placementByTeam.get(t);
                if (place != null && place == 1) {
                    session.setPlacement(t, 2);
                }
            }
        } else {
            session.winningTeam = null;
            session.result = "TIE";
            for (Team t : top) session.setPlacementIfAbsent(t, 1);
        }
    }

    private Collection<Team> getArenaTeamsSafe(Arena arena, MatchSession session) {
        try {
            Method m = arena.getClass().getMethod("getTeams");
            Object o = m.invoke(arena);
            if (o instanceof Collection<?> c) {
                List<Team> out = new ArrayList<>();
                for (Object obj : c) if (obj instanceof Team t) out.add(t);
                return out;
            }
        } catch (Throwable ignored) {}

        // Fallback: whatever we observed during the match.
        return session != null ? session.participatingTeams : List.of();
    }

    private boolean isTeamBedGone(Team team) {
        if (team == null) return false;

        // Try a variety of common method names across MBedwars versions.
        String[] candidates = {
                "isBedDestroyed",
                "isBedBroken",
                "isBedGone",
                "hasBed",
                "isBedAlive"
        };

        for (String name : candidates) {
            try {
                Method m = team.getClass().getMethod(name);
                Object o = m.invoke(team);
                if (o instanceof Boolean b) {
                    // hasBed / isBedAlive are inverted semantics
                    if (name.equals("hasBed") || name.equals("isBedAlive")) return !b;
                    return b;
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    // ----------------------------------------------------------------------
    // Critical stat events (kills/deaths/beds)
    // ----------------------------------------------------------------------

    public void onIngameDeath(Arena arena, Player victim, boolean fatalDeath, boolean countingDeathStats,
                              EntityDamageEvent.DamageCause cause) {
        if (arena == null || victim == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = victim.getUniqueId();
        session.unmarkSpectatorOnly(uuid);
        session.addParticipant(uuid);
        session.setUsername(uuid, victim.getName());
        Team team = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, team);

        String causeName = cause != null ? cause.name() : null;

        if (!countingDeathStats) {
            // Still log the event but skip stat increment.
            session.addEvent(MatchEvent.playerDeath(now(), uuid.toString(), victim.getName(),
                    team != null ? team.name() : null, fatalDeath, causeName));
            if (fatalDeath && team != null) {
                session.markPlayerFinalDead(team, uuid);
                maybeMarkTeamEliminated(arena, session, team);
            }
            return;
        }

        session.addTriggerIncrement(uuid, "bedwars:deaths", 1L);
        session.addEvent(MatchEvent.playerDeath(now(), uuid.toString(), victim.getName(),
                team != null ? team.name() : null, fatalDeath, causeName));
        if (fatalDeath) {
            session.addTriggerIncrement(uuid, "bedwars:final_deaths", 1L);

            // Placement tracking: fatal deaths remove the player from alive list.
            if (team != null) {
                session.markPlayerFinalDead(team, uuid);
                maybeMarkTeamEliminated(arena, session, team);
            }
        }
    }

    public void onKill(Arena arena, Player killer, Player victim, boolean fatalDeath, boolean countingKillStats,
                       EntityDamageEvent.DamageCause cause) {
        if (arena == null || killer == null || !countingKillStats) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = killer.getUniqueId();
        session.unmarkSpectatorOnly(uuid);
        session.addParticipant(uuid);
        session.setUsername(uuid, killer.getName());
        Team killerTeam = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, killerTeam);

        session.addTriggerIncrement(uuid, "bedwars:kills", 1L);
        String victimName = victim != null ? victim.getName() : null;
        session.addEvent(MatchEvent.playerKill(now(), uuid.toString(), killer.getName(),
                killerTeam != null ? killerTeam.name() : null, victimName, fatalDeath,
                cause != null ? cause.name() : null));
        if (fatalDeath) {
            session.addTriggerIncrement(uuid, "bedwars:final_kills", 1L);
        }
    }

    /**
     * Called when MBedwars confirms a team has been fully eliminated.
     * This is the most reliable placement signal — it fires after both bed destruction
     * and all member deaths have been resolved, regardless of the order events arrive.
     */
    public void onTeamEliminate(Arena arena, Team eliminatedTeam) {
        if (arena == null || eliminatedTeam == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        // Treat as bed-lost even if we missed the bed break event.
        session.bedLostTeams.add(eliminatedTeam);

        // Mark all alive players on this team as finally dead.
        Set<UUID> alive = new HashSet<>(session.alivePlayersByTeam.getOrDefault(eliminatedTeam, Set.of()));
        for (UUID uuid : alive) {
            session.markPlayerFinalDead(eliminatedTeam, uuid);
        }

        session.addEvent(MatchEvent.teamEliminate(now(), eliminatedTeam.name()));
        maybeMarkTeamEliminated(arena, session, eliminatedTeam);
    }

    /**
     * Called when a spectator who was never a real participant joins an arena.
     * Reasons LOSE and DEATH indicate an eliminated player becoming an in-game spectator;
     * those are handled via onIngameDeath and must not be marked spectator-only.
     */
    public void onSpectatorJoinExternal(Arena arena, Player player) {
        if (arena == null || player == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = player.getUniqueId();
        // If the player already has a team in this match they're a real participant.
        if (session.getTeam(uuid) != null) return;

        session.markSpectatorOnly(uuid);
        session.unmarkPending(uuid);
        session.setUsername(uuid, player.getName());
        if (session.started && session.tryLogJoin(uuid)) {
            session.addEvent(MatchEvent.spectatorJoin(now(), uuid.toString(), player.getName()));
        }
        cacheMatchId(uuid, arena.getName(), session.matchId);
    }

    /**
     * Called when MBedwars assigns or changes a player's team (e.g. during lobby assignment, or
     * a mid-match auto-balance/team switch). Promotes pending players to real participants the
     * moment they receive a team.
     */
    public void onPlayerTeamAssigned(Arena arena, Player player, Team oldTeam, Team newTeam) {
        if (arena == null || player == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = player.getUniqueId();

        // A switch (or removal) away from a team must clean up that team's alive-tracking —
        // otherwise the switching player stays a "ghost" alive entry on their old team forever
        // (their future deaths get attributed to the new team instead), which can permanently
        // block the old team from ever being detected as fully eliminated.
        if (oldTeam != null && !oldTeam.equals(newTeam)) {
            session.markPlayerFinalDead(oldTeam, uuid);
            maybeMarkTeamEliminated(arena, session, oldTeam);
        }

        if (newTeam == null) return; // team removed, not (re)assigned — nothing further to promote

        session.promoteToParticipant(uuid, newTeam);
        session.setUsername(uuid, player.getName());
        session.markTeamParticipating(newTeam);
        session.markPlayerAlive(newTeam, uuid);
        cacheMatchId(uuid, arena.getName(), session.matchId);
    }

    public void onBedBreak(Arena arena, Player breaker, Team bedTeam) {
        if (arena == null || breaker == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = breaker.getUniqueId();
        session.unmarkSpectatorOnly(uuid);
        session.addParticipant(uuid);
        session.setUsername(uuid, breaker.getName());
        Team breakerTeam = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, breakerTeam);

        session.addTriggerIncrement(uuid, "bedwars:beds_destroyed", 1L);
        session.addEvent(MatchEvent.bedBreak(now(), uuid.toString(), breaker.getName(),
                breakerTeam != null ? breakerTeam.name() : null,
                bedTeam != null ? bedTeam.name() : null));

        // Placement tracking: record which team lost their bed (NOT the breaking player's team).
        if (bedTeam != null) {
            session.bedLostTeams.add(bedTeam);
            maybeMarkTeamEliminated(arena, session, bedTeam);
        }
    }

    /**
     * Flush all current sessions (e.g., on disable). This will only persist sessions with real match activity.
     */
    public void flushAll(String reason) {
        flushAll(reason, false);
    }

    /**
     * Flush all current sessions.
     *
     * @param sync When true, saves are performed synchronously on the calling thread instead of being
     *             scheduled via the Bukkit scheduler. This MUST be used from onDisable(): Bukkit marks
     *             the plugin as disabled before invoking onDisable(), so runTaskAsynchronously() throws
     *             IllegalPluginAccessException immediately (outside any try/catch here), silently
     *             dropping every remaining session in this batch.
     */
    public void flushAll(String reason, boolean sync) {
        if (reason != null) plugin.getLogger().warning("Matchbook: flushing matches due to " + reason);
        Map<String, MatchSession> copy = new LinkedHashMap<>(sessionsByArena);
        sessionsByArena.clear();

        for (MatchSession session : copy.values()) {
            session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);

            String result = session.result != null ? session.result : "ABORTED";
            MatchDocument doc = MatchDocument.fromSession(session, result);

            if (!shouldPersist(session, doc)) continue;

            persistMatch(doc, sync);
        }
    }

    // -----------------
    // Internals
    // -----------------

    /**
     * Persistence retry budget. Fetching {@code plugin.getRepo()} fresh on every attempt means a
     * retry can succeed even if the earlier failure was a bad storage config that an admin just
     * fixed with {@code /mb reload} — no restart needed either way.
     */
    private static final int SAVE_MAX_ATTEMPTS = 3;
    private static final long SAVE_RETRY_DELAY_TICKS = 40L; // 2s, only used on the async path

    /**
     * Saves a match with a bounded retry, and — only if every attempt fails — writes a local YAML
     * recovery copy under matches/failed/ so a transient storage outage (DB down, bad credentials,
     * disk hiccup) can never silently lose a match's data. Never throws.
     *
     * @param sync Must be true only from onDisable(): the scheduler refuses new async/delayed tasks
     *             once the plugin is marked disabled, so this path retries synchronously and inline
     *             instead of scheduling delayed attempts.
     */
    private void persistMatch(MatchDocument doc, boolean sync) {
        if (sync) {
            for (int attempt = 1; attempt <= SAVE_MAX_ATTEMPTS; attempt++) {
                try {
                    plugin.getRepo().saveMatch(doc);
                    return;
                } catch (Exception e) {
                    plugin.getLogger().warning("Matchbook: save attempt " + attempt + "/" + SAVE_MAX_ATTEMPTS
                            + " failed for match " + doc.matchId() + ": " + e.getMessage());
                    if (attempt < SAVE_MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(300L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            writeRecoveryCopy(doc);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> attemptSaveAsync(doc, 1));
        }
    }

    private void attemptSaveAsync(MatchDocument doc, int attempt) {
        try {
            plugin.getRepo().saveMatch(doc);
            return;
        } catch (Exception e) {
            plugin.getLogger().warning("Matchbook: save attempt " + attempt + "/" + SAVE_MAX_ATTEMPTS
                    + " failed for match " + doc.matchId() + ": " + e.getMessage());
        }

        if (attempt >= SAVE_MAX_ATTEMPTS) {
            writeRecoveryCopy(doc);
            return;
        }

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                () -> attemptSaveAsync(doc, attempt + 1), SAVE_RETRY_DELAY_TICKS);
    }

    /** Last-resort safety net so a persistent storage outage never means lost match data. */
    private void writeRecoveryCopy(MatchDocument doc) {
        plugin.getLogger().severe("Matchbook: giving up saving match " + doc.matchId()
                + " to the configured storage backend after " + SAVE_MAX_ATTEMPTS
                + " attempts. Writing a local recovery copy instead.");
        try {
            File dir = new File(plugin.getAddonDataFolder(), "matches" + File.separator + "failed");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("could not create recovery folder " + dir.getAbsolutePath());
            }
            File out = new File(dir, doc.matchId() + ".yml");
            MatchYamlCodec.toYaml(doc).save(out);
            plugin.getLogger().severe("Matchbook: recovery copy written to " + out.getAbsolutePath()
                    + " — once storage is healthy again (see /mb test), this match can be recovered manually "
                    + "from that file.");
        } catch (Exception e) {
            plugin.getLogger().severe("Matchbook: FAILED to write a local recovery copy for match "
                    + doc.matchId() + ": " + e.getMessage() + " — this match's data is lost.");
        }
    }

    private void finishMatch(Arena arena, MatchSession session, String result) {
        session.addEvent(MatchEvent.matchEnd(now()));

        // Cache matchId for participants so placeholders remain stable during post-game transitions.
        for (UUID u : session.getParticipants()) {
            cacheMatchId(u, arena != null ? arena.getName() : session.arenaName, session.matchId);
        }

        // Remove the session now (we have what we need). This prevents late events from touching it.
        // Keyed removal only: this can run on an async stats-callback thread, and if the arena has
        // already started its next round (which evicted this ended session and mapped a fresh one),
        // a blind remove(name) would kill the NEW round's session instead.
        sessionsByArena.remove(arena.getName(), session);

        session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);
        MatchDocument doc = MatchDocument.fromSession(session, result);

        // Requirement: only save matches if kills/deaths/bed breaks happened.
        if (!shouldPersist(session, doc)) {
            return;
        }

        persistMatch(doc, false);
    }

    private boolean shouldPersist(MatchSession session, MatchDocument doc) {
        // Never persist a match whose recorded duration exceeds the sane maximum — this is the
        // shutdown-time safety net for the watchdog's proactive check in startAbortWatchdog(). A
        // session that never received a real RoundEnd and lingers until the server restarts would
        // otherwise be saved with startUnix from hours/days earlier and endUnix = shutdown time,
        // producing a bogus "duplicate-looking" match with an absurd running time.
        if (exceedsMaxDuration(doc)) {
            plugin.getLogger().warning("Matchbook: discarding match " + (doc != null ? doc.matchId() : "?")
                    + " (arena=" + (doc != null ? doc.arenaName() : "?") + ") — its recorded duration exceeds "
                    + "match.max_duration_minutes. This usually means the match never received a proper end "
                    + "(e.g. it was still lingering when the server restarted) and is not a real match.");
            return false;
        }

        // Prefer event-driven signal, but fall back to inspecting the document.
        if (session != null && session.hasTriggerActivity()) return true;
        return hasAnyNonZeroDiffForKeys(doc, PERSIST_TRIGGER_KEYS);
    }

    /** Config-driven ceiling on match duration. 0 (or unset) means no limit. */
    private long maxDurationMinutes() {
        return plugin.getMatchbookConfig().raw().getLong("match.max_duration_minutes", 180L);
    }

    private boolean exceedsMaxDuration(MatchDocument doc) {
        if (doc == null) return false;
        long durationSeconds = doc.endUnix() - doc.startUnix();
        // A negative duration is always broken data (e.g. a session whose startUnix was overwritten
        // after its end was recorded) — reject it even when max_duration is disabled.
        if (durationSeconds < 0L) return true;
        long maxMinutes = maxDurationMinutes();
        if (maxMinutes <= 0L) return false;
        return durationSeconds > maxMinutes * 60L;
    }

    private boolean hasAnyNonZeroDiffForKeys(MatchDocument doc, Set<String> keys) {
        if (doc == null || doc.players() == null || keys == null || keys.isEmpty()) return false;

        for (MatchDocument.PlayerEntry pe : doc.players().values()) {
            Map<String, Long> diff = pe.diff();
            if (diff == null) continue;
            for (String k : keys) {
                Long v = diff.get(k);
                if (v != null && v != 0L) return true;
            }
        }
        return false;
    }

    private boolean shouldDisableRejoin(KickReason reason) {
        // Configurable; defaults to true for LEAVE/TELEPORT and arena-switch reasons.
        boolean disableOnLeave = plugin.getMatchbookConfig().raw().getBoolean("rejoin.disable_on_leave", true);
        boolean disableOnTeleport = plugin.getMatchbookConfig().raw().getBoolean("rejoin.disable_on_teleport", true);
        boolean disableOnSwitchArena = plugin.getMatchbookConfig().raw().getBoolean("rejoin.disable_on_switch_arena", true);

        if (reason == null) return false;
        String n = reason.name();
        if (disableOnLeave && n.equals("LEAVE")) return true;
        if (disableOnTeleport && n.equals("TELEPORT")) return true;
        if (disableOnSwitchArena && n.endsWith("_SWITCH_ARENA")) return true;
        return false;
    }

    private void takeStartSnapshots(Arena arena, MatchSession session) {
        long startDelay = plugin.getMatchbookConfig().runtimeSettings().startSnapshotDelayTicks();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // snapshot all current players in arena
            Set<UUID> uuids = new HashSet<>();
            for (Player p : arena.getPlayers()) uuids.add(p.getUniqueId());

            session.startSnapshotTakenUnix = System.currentTimeMillis() / 1000L;

            takeSnapshots(uuids, snap -> {
                session.putStart(snap.uuid, snap.snapshot);
                session.setStartTakenUnix(snap.uuid, System.currentTimeMillis() / 1000L);
            }, null);

        }, startDelay);
    }

    private void takeEndSnapshots(Arena arena, MatchSession session, Runnable onComplete) {
        // Only snapshot currently-in-arena online players.
        Set<UUID> uuids = new HashSet<>();
        for (Player p : arena.getPlayers()) uuids.add(p.getUniqueId());

        takeSnapshots(uuids, snap -> session.putEnd(snap.uuid, snap.snapshot), onComplete);
    }

    /**
     * Build per-match stats for all participants.
     *
     * Sources (in order):
     *  1) already-captured session.matchStats (from PlayerQuitArenaEvent)
     *  2) Arena QuitPlayerMemory game stats
     *  3) live PlayerDataAPI game stats (ONLY for players still in this arena)
     */
    private void captureMatchStatsFromArena(Arena arena, MatchSession session, Runnable onComplete) {
        Set<UUID> onlineInArena = new HashSet<>();
        for (Player p : arena.getPlayers()) onlineInArena.add(p.getUniqueId());

        // First, fill any missing from QuitPlayerMemory
        for (UUID uuid : session.getParticipants()) {
            if (session.getMatchStats(uuid) != null) continue;

            QuitPlayerMemory mem = null;
            try {
                mem = arena.getQuitPlayerMemory(uuid);
            } catch (Throwable ignored) {
            }

            if (mem != null) {
                session.putMatchStats(uuid, snapshotFromGameStatsMap(mem.getGameStats()));
            }
        }

        // Now fill missing from live PlayerDataAPI game stats (safe ONLY for players still in this arena)
        Set<UUID> remaining = new HashSet<>();
        for (UUID uuid : session.getParticipants()) {
            if (session.getMatchStats(uuid) == null && onlineInArena.contains(uuid)) {
                remaining.add(uuid);
            }
        }

        if (remaining.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        takeGameSnapshots(remaining, snap -> session.putMatchStats(snap.uuid, snap.snapshot), onComplete);
    }

    private void takeSnapshots(Set<UUID> uuids, Consumer<UuidSnapshot> consumer, Runnable onComplete) {
        if (uuids == null || uuids.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(uuids.size());
        AtomicBoolean finished = new AtomicBoolean(false);

        for (UUID uuid : uuids) {
            snapshotTrackedStats(uuid, snap -> {
                try {
                    consumer.accept(new UuidSnapshot(uuid, snap));
                } finally {
                    int left = remaining.decrementAndGet();
                    if (left <= 0 && finished.compareAndSet(false, true)) {
                        if (onComplete != null) onComplete.run();
                    }
                }
            });
        }

        long timeout = plugin.getMatchbookConfig().runtimeSettings().snapshotTimeoutTicks();
        if (onComplete != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int left = remaining.get();
                if (left > 0 && finished.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Matchbook: snapshot timeout; completing with partial data. remaining=" + left);
                    onComplete.run();
                }
            }, timeout);
        }
    }

    private void takeGameSnapshots(Set<UUID> uuids, Consumer<UuidSnapshot> consumer, Runnable onComplete) {
        if (uuids == null || uuids.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(uuids.size());
        AtomicBoolean finished = new AtomicBoolean(false);

        for (UUID uuid : uuids) {
            snapshotGameTrackedStats(uuid, snap -> {
                try {
                    consumer.accept(new UuidSnapshot(uuid, snap));
                } finally {
                    int left = remaining.decrementAndGet();
                    if (left <= 0 && finished.compareAndSet(false, true)) {
                        if (onComplete != null) onComplete.run();
                    }
                }
            });
        }

        long timeout = plugin.getMatchbookConfig().runtimeSettings().snapshotTimeoutTicks();
        if (onComplete != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int left = remaining.get();
                if (left > 0 && finished.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Matchbook: game-snapshot timeout; completing with partial data. remaining=" + left);
                    onComplete.run();
                }
            }, timeout);
        }
    }

    private void snapshotTrackedStats(UUID uuid, Consumer<StatSnapshot> consumer) {
        List<String> keys = plugin.getSettings() != null ? plugin.getSettings().trackedKeys() : plugin.getMatchbookConfig().trackedKeys();

        playerDataApi.getStats(uuid, statsObj -> {
            PlayerStats chosen = BedwarsStatsAdapter.pickBest(statsObj);
            if (chosen == null) {
                consumer.accept(StatSnapshot.empty(keys));
                return;
            }

            Map<String, Long> out = new LinkedHashMap<>();
            for (String key : keys) {
                Number n = chosen.get(key);
                out.put(key, n == null ? 0L : n.longValue());
            }

            consumer.accept(new StatSnapshot(out));
        });
    }

    /**
     * Attempts to snapshot *game stats* (per-match stats).
     */
    private void snapshotGameTrackedStats(UUID uuid, Consumer<StatSnapshot> consumer) {
        List<String> keys = plugin.getSettings() != null ? plugin.getSettings().trackedKeys() : plugin.getMatchbookConfig().trackedKeys();

        playerDataApi.getStats(uuid, statsObj -> {
            PlayerStats game = extractGameStats(statsObj);
            if (game == null) {
                consumer.accept(StatSnapshot.empty(keys));
                return;
            }

            Map<String, Long> out = new LinkedHashMap<>();
            for (String key : keys) {
                Number n = game.get(key);
                out.put(key, n == null ? 0L : n.longValue());
            }

            consumer.accept(new StatSnapshot(out));
        });
    }

    /**
     * Extracts the per-game PlayerStats from whatever object MBedwars hands us.
     */
    private PlayerStats extractGameStats(Object statsObj) {
        if (statsObj == null) return null;

        if (statsObj instanceof PlayerStats ps) {
            try {
                return ps.isGameStats() ? ps : ps.getGameStats();
            } catch (Throwable t) {
                return ps;
            }
        }

        // MBedwars sometimes returns a wrapper with getGameStats()/getOverallStats().
        try {
            Method m = statsObj.getClass().getMethod("getGameStats");
            Object o = m.invoke(statsObj);
            if (o instanceof PlayerStats ps) return ps;
        } catch (Throwable ignored) {
        }

        // Fallback: choose best and attempt to derive game stats.
        PlayerStats chosen = BedwarsStatsAdapter.pickBest(statsObj);
        if (chosen == null) return null;
        try {
            return chosen.isGameStats() ? chosen : chosen.getGameStats();
        } catch (Throwable t) {
            return chosen;
        }
    }

    private StatSnapshot snapshotFromGameStatsMap(Map<String, ? extends Number> stats) {
        List<String> keys = plugin.getSettings() != null ? plugin.getSettings().trackedKeys() : plugin.getMatchbookConfig().trackedKeys();

        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : keys) {
            Number n = stats != null ? stats.get(key) : null;
            out.put(key, n == null ? 0L : n.longValue());
        }
        return new StatSnapshot(out);
    }

    private void startAbortWatchdog(Arena arena, MatchSession session) {
        // Poll arena status; if the session is still present but arena is no longer running/starting, consider it aborted.
        new BukkitRunnable() {
            @Override
            public void run() {
                MatchSession current = sessionsByArena.get(arena.getName());
                if (current == null || current != session) {
                    cancel();
                    return;
                }

                // Proactively kill matches that have been running (without a real RoundEnd) for
                // longer than makes sense — regardless of what arena.getStatus() reports. This is
                // the actual fix for the "duplicate match with an absurd running time" bug: a match
                // that never gets a RoundEnd (e.g. MBedwars leaves the arena stuck reporting RUNNING
                // forever) used to just sit in sessionsByArena until the server restarted, at which
                // point onDisable()'s flushAll() would save it with startUnix from hours/days earlier
                // and endUnix = shutdown time. Catching it here means it's discarded outright and
                // never reaches persistence at all.
                if (current.endUnix == null) {
                    long maxMinutes = maxDurationMinutes();
                    if (maxMinutes > 0L) {
                        long elapsedMinutes = (System.currentTimeMillis() / 1000L - current.startUnix) / 60L;
                        if (elapsedMinutes > maxMinutes) {
                            cancel();
                            plugin.getLogger().warning("Matchbook: discarding stuck match on arena=" + arena.getName()
                                    + " (matchId=" + current.matchId + ") — running " + elapsedMinutes
                                    + "m without a round end, which exceeds match.max_duration_minutes ("
                                    + maxMinutes + "). Not saving; this match is considered broken.");
                            sessionsByArena.remove(arena.getName(), current);
                            return;
                        }
                    }
                }

                ArenaStatus status = arena.getStatus();
                if (status == ArenaStatus.RUNNING) return;

                // If match ended normally we remove session in onRoundEnd save callback.
                // Here, if arena is no longer RUNNING but we never got RoundEnd, save ABORTED.
                if (current.endUnix == null) {
                    cancel();
                    plugin.getLogger().warning("Matchbook: match aborted (no RoundEnd). arena=" + arena.getName() + " status=" + status);

                    // Try to capture any remaining match stats before saving.
                    captureMatchStatsFromArena(arena, current, () -> {
                        current.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);

                        MatchDocument doc = MatchDocument.fromSession(current, "ABORTED");
                        if (!shouldPersist(current, doc)) return;

                        persistMatch(doc, false);
                    });

                    sessionsByArena.remove(arena.getName(), current);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

/**
     * Resolve a player's team by checking Arena membership.
     */
    private Team resolveTeamFromArena(Arena arena, UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return null;
        try {
            return arena.getPlayerTeam(p);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Best-effort spectator detection across MBedwars versions.
     *
     * NOTE: Eliminated participants often become spectators; we only use this at JOIN time
     * to mark "spectator-only" viewers who were never on a team.
     */
    private boolean isSpectator(Arena arena, Player player) {
        if (arena == null || player == null) return false;

        try {
            var m = arena.getClass().getMethod("isSpectator", Player.class);
            Object o = m.invoke(arena, player);
            if (o instanceof Boolean b) return b;
        } catch (Throwable ignored) {}

        try {
            var m = arena.getClass().getMethod("getSpectators");
            Object o = m.invoke(arena);
            if (o instanceof Collection<?> c) return c.contains(player) || c.contains(player.getUniqueId());
        } catch (Throwable ignored) {}

        return false;
    }

    /**
     * Returns true if the team is considered alive at end-of-match.
     * Prefer our tracked alivePlayersByTeam, but fall back to reflective team APIs if needed.
     */
    private boolean isTeamAliveAtEnd(Team team, MatchSession session) {
        if (team == null || session == null) return false;

        Set<UUID> alive = session.alivePlayersByTeam.get(team);
        if (alive != null) return !alive.isEmpty();

        // Reflection fallbacks across MBedwars versions
        String[] boolMethods = {"isAlive", "isLiving", "isEliminated", "isDead"};
        for (String name : boolMethods) {
            try {
                Method m = team.getClass().getMethod(name);
                Object o = m.invoke(team);
                if (o instanceof Boolean b) {
                    if (name.equals("isEliminated") || name.equals("isDead")) return !b;
                    return b;
                }
            } catch (Throwable ignored) {}
        }

        String[] countMethods = {"getAlivePlayerCount", "getAlivePlayersCount"};
        for (String name : countMethods) {
            try {
                Method m = team.getClass().getMethod(name);
                Object o = m.invoke(team);
                if (o instanceof Number n) return n.intValue() > 0;
            } catch (Throwable ignored) {}
        }

        String[] collMethods = {"getAlivePlayers", "getPlayersAlive"};
        for (String name : collMethods) {
            try {
                Method m = team.getClass().getMethod(name);
                Object o = m.invoke(team);
                if (o instanceof Collection<?> c) return !c.isEmpty();
            } catch (Throwable ignored) {}
        }

        // Unknown -> assume not alive (conservative). Our normal tracking should cover real matches.
        return false;
    }

    private static String computeResult(MatchSession session) {
        if (session == null) return "UNKNOWN";
        if (session.result != null && !session.result.isBlank()) return session.result;
        if (session.winningTeam != null) return "WIN:" + session.winningTeam.name();
        return "UNKNOWN";
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static final class UuidSnapshot {
        final UUID uuid;
        final StatSnapshot snapshot;

        UuidSnapshot(UUID uuid, StatSnapshot snapshot) {
            this.uuid = uuid;
            this.snapshot = snapshot;
        }
    }
}
