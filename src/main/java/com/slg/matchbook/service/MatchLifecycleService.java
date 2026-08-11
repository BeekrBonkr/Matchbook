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
 * Approach (0.7.5+):
 *  - The recorded event log is the source of truth for exported stats — MatchDocument.fromSession
 *    derives kills, deaths, final kills/deaths, beds destroyed and beds lost from it directly.
 *  - MBedwars *per-round* ("game") stats (QuitPlayerMemory / PlayerStats#getGameStats) and the
 *    event-driven trigger counters are still captured, but only as a diagnostic cross-check
 *    (stat_mismatch warnings), for custom tracked keys the event log can't derive, and as the
 *    persistence trigger.
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
     * Looks up an arena's session, but only if it's still open for mutation (endUnix == null).
     *
     * Once RoundEnd sets endUnix, the session is already queued for the end-snapshot/finalize/save
     * chain — that chain can take several seconds (async stat callbacks under load). Straggler events
     * that arrive during that window (a delayed kill/death/team-change/quit for a player transitioning
     * out) must not mutate a match that's already being closed out and saved; they belong to whatever
     * comes next on this arena, not this one. Event handlers that only ever react to (never create)
     * a session should look it up through here instead of sessionsByArena directly.
     */
    private MatchSession liveSession(String arenaName) {
        MatchSession session = sessionsByArena.get(arenaName);
        return (session != null && session.endUnix == null) ? session : null;
    }

    /**
     * Captures the session an in-match event belongs to, at the moment the event fires.
     *
     * MBedwars can deliver kills, deaths, bed breaks and team eliminations asynchronously, and the
     * listener has to bounce those onto the main thread before they can touch anything — which lands
     * them a tick later. Re-resolving the session at that point would ask "what is live NOW", and the
     * answer for the match-deciding final kill is "nothing": RoundEnd has already run and stamped
     * endUnix, so {@link #liveSession} returns null and the event is silently discarded.
     *
     * Pinning here instead means such an event stays attached to the round it actually happened in.
     * This is not a loophole in the ended-session guard: a session is only ever pinned while it is
     * genuinely live, so a straggler that fires AFTER the round ended still resolves to null (or to
     * the next round's session, where it belongs). Safe to call off the main thread — it reads a
     * concurrent map and a volatile field.
     */
    public MatchSession pinLiveSession(Arena arena) {
        return arena != null ? liveSession(arena.getName()) : null;
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
        // Teams that actually have a player on them right now — the real field for this round.
        Set<Team> roundStartTeams = new HashSet<>();

        for (Player p : arena.getPlayers()) {
            Team team = resolveTeamFromArena(arena, p.getUniqueId());

            // A teamless viewer who is already classified as watching this match stays a spectator.
            // Whether arena.getPlayers() includes external spectators varies across MBedwars builds;
            // if it does, promoting them here (unmarkSpectatorOnly + addParticipant) would undo a
            // correct classification made moments earlier and put a viewer in the match roster.
            if (team == null && (isSpectator(arena, p) || session.isSpectatorOnly(p.getUniqueId()))) {
                session.setUsername(p.getUniqueId(), p.getName());
                continue;
            }

            session.unmarkSpectatorOnly(p.getUniqueId());
            session.addParticipant(p.getUniqueId());
            session.markRoundRoster(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
            session.setTeam(p.getUniqueId(), team);
            if (team != null) {
                roundStartTeams.add(team);
                session.markTeamParticipating(team);
                session.markPlayerAlive(team, p.getUniqueId());
                captureMatchStatsBaseline(p.getUniqueId(), session);
            }

            cacheMatchId(p.getUniqueId(), arena.getName(), session.matchId);
        }

        // Every team's roster should be known by now; freeze totalTeams() so that a very early
        // elimination can't compute placement against a still-growing, undercounted team set.
        // Freeze against the teams counted above, NOT session.participatingTeams — that set also
        // holds teams a player only ever picked in the lobby team selector before switching away,
        // which are empty by the time the round starts and would inflate every placement.
        session.freezeTotalTeams(roundStartTeams.size());

        // The roster is now known, so "was this player part of this round?" becomes answerable —
        // which is what keeps a stale round-end roster entry from another round out of the match.
        session.markRoundRosterCaptured();
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
        // Joining the arena while the round is running (a rejoin, or an admin dropping in) is a
        // first-hand observation that this player is part of THIS round, same as the round-start
        // roster. Pre-round lobby joins aren't: whoever is actually playing is settled by the
        // round-start capture, and someone who picked a team and left before the round began
        // never played.
        if (session.started) session.markRoundRoster(uuid);
        captureMatchStatsBaseline(uuid, session);
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
        MatchSession session = liveSession(arena.getName());
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

        MatchSession session = liveSession(arena.getName());
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
            recordMatchStats(session, uuid, snapshotFromGameStatsMap(mem.getGameStats()));

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
            snapshotGameTrackedStats(uuid, snap -> recordMatchStatsIfGeneration(session, uuid, snap, gen));
        }
    }

    public void onRoundEnd(Arena arena, Collection<Player> winners, Collection<Player> losers,
                          Collection<QuitPlayerMemory> quitWinners, Collection<QuitPlayerMemory> quitLosers) {
        if (arena == null) return;

        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        // Duplicate RoundEnd (MBedwars can re-fire it under forced-stop conditions): the first one
        // already scheduled the finalize chain. Running it twice would save the match twice and
        // stamp every player's matchbook:*_place counter twice.
        if (session.endUnix != null) return;

        session.endUnix = System.currentTimeMillis() / 1000L;

        // Roster for the match that just ended, taken directly from RoundEndEvent — NOT from
        // arena.getPlayers(). arena.getPlayers() reflects live, *current* arena occupancy, which by
        // the time this handler runs can already include players who queued into this same arena's
        // NEXT round (auto-requeue, popular arenas). Those players have no team yet; adding them here
        // used to leak them into this match's document as a participant with an empty team, and any
        // later live-stats fallback would then read their per-round game-stats object before MBedwars
        // resets it for their own upcoming round — i.e. their totals from the last time they actually
        // played this map. RoundEndEvent's winner/loser rosters (plus the offline QuitPlayerMemory
        // buckets for anyone who already left) are exactly who played THIS round, frozen at the moment
        // the round actually ended.
        for (Player p : winners) addRoundEndParticipant(arena, session, p);
        for (Player p : losers) addRoundEndParticipant(arena, session, p);
        for (QuitPlayerMemory mem : quitWinners) addRoundEndQuitParticipant(session, mem);
        for (QuitPlayerMemory mem : quitLosers) addRoundEndQuitParticipant(session, mem);

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

                    // Collapse the standings to a contiguous 1..N ranking. Places are derived
                    // arithmetically from a team count frozen at round start, so any drift between
                    // that count and the teams that actually finished shows up as a skipped rank
                    // (or two teams sharing one). Order is preserved; only the numbers are re-seated.
                    session.normalizePlacements();

                    // Placements are definitive now, so a "TIE" they don't support can be corrected.
                    reconcileTieWithPlacements(session);

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
        Set<Team> teamsWithPlayers = new HashSet<>();
        for (UUID u : session.getParticipants()) {
            Team t = session.getTeam(u);
            if (t != null) {
                teamsWithPlayers.add(t);
                session.markTeamParticipating(t);
            }
        }

        // Discard teams nobody ended the match on. participatingTeams accumulates every team any
        // player was ever assigned to, including a team someone picked in the lobby selector and
        // then switched away from (PlayerTeamChangeEvent fires pre-round too). Such a team has zero
        // players, but it still falls through the placement loops below and takes a rank — and since
        // applyPlacementsToMatchStats only stamps ranks onto players, that rank lands on nobody and
        // the standings come out with a hole in them (the reported "1st, 3rd, 4th, no 2nd").
        for (Team t : new ArrayList<>(session.participatingTeams)) {
            if (t != null && !teamsWithPlayers.contains(t)) session.dropTeam(t);
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

        // More than one team still standing when the round ended means nobody was played out of the
        // match: it ran to the time limit (or was force-ended) with every survivor still in it. Those
        // teams tied for 1st.
        //
        // MBedwars may still name a winning team in that situation — some builds/setups break a
        // time-limit end with their own tiebreak (most beds, most kills). That is a decision about
        // who to *reward*, not a record of the match having been won, and taking it at face value is
        // what turned a three-way tie into "1st, 2nd, 2nd": the reported team was stamped 1st and the
        // other two survivors dropped to runner-up. Survivors win the disagreement by default; set
        // match.multiple_survivors_are_a_tie to false to go back to trusting the reported winner.
        //
        // The override is deliberately limited to the case where the reported winner is itself one of
        // the surviving teams. If MBedwars names a winner we don't even have alive, our own alive
        // tracking is the unreliable one and it has no business overruling anything.
        boolean multipleSurvivors = aliveAtEnd.size() > 1;
        boolean overrideReportedWinner = multipleSurvivors
                && session.winningTeam != null
                && aliveAtEnd.contains(session.winningTeam)
                && survivorsOutrankReportedWinner();

        tieByAliveTeams = multipleSurvivors && (session.winningTeam == null || overrideReportedWinner);
        if (tieByAliveTeams) {
            if (overrideReportedWinner) {
                plugin.getLogger().info("Matchbook: match " + session.matchId + " (arena=" + session.arenaName
                        + ") ended with " + aliveAtEnd.size() + " teams still standing ("
                        + teamNames(aliveAtEnd) + "), but MBedwars reported " + session.winningTeam.name()
                        + " as the winning team. Recording it as a tie between the surviving teams — set "
                        + "match.multiple_survivors_are_a_tie to false in config.yml to record MBedwars' "
                        + "winner instead.");
            }
            session.result = "TIE";
            session.winningTeam = null;
            session.tieBySurvivors = true;
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

    /** Config gate for letting surviving teams overrule a winning team MBedwars reported anyway. */
    private boolean survivorsOutrankReportedWinner() {
        return plugin.getMatchbookConfig().raw().getBoolean("match.multiple_survivors_are_a_tie", true);
    }

    private static String teamNames(Collection<Team> teams) {
        List<String> names = new ArrayList<>();
        for (Team t : teams) if (t != null) names.add(t.name());
        Collections.sort(names);
        return String.join(", ", names);
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

        // A tie decided by teams surviving to the end is a fact about the match, not a missing
        // winner to be recovered. Inferring here would undo it: MBedwars hands the survivor it
        // picked a bedwars:wins, and this method would read that single win, demote the other
        // survivors from 1st to 2nd, and reproduce the exact standings the survivor rule fixed.
        if (session.tieBySurvivors) return;

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

    /**
     * Promotes a "TIE" that the final standings don't actually support into the win it really was.
     *
     * A tie means two or more teams finished level at the top. If the standings show exactly one team
     * at 1st, nobody tied — the match had a clear winner that MBedwars simply didn't report (the same
     * null-winner quirk {@link #inferResultFromRecordedWinStats} exists for, hit when that method's
     * bedwars:wins signal is also missing, so it can't rescue the result either).
     *
     * Left unreconciled, the sole survivor was written as 1st place AND credited with a loss, because
     * MatchDocument treats every team outside the tied-for-1st set as an outright loser.
     *
     * Deliberately conservative: a genuine tie (more than one team at 1st) is never touched, and a
     * single-team match — where "1st place" carries no information — is ignored.
     */
    private void reconcileTieWithPlacements(MatchSession session) {
        if (session == null || session.winningTeam != null) return;
        if (session.result == null || !session.result.equalsIgnoreCase("TIE")) return;
        if (session.placementByTeam.size() < 2) return;

        List<Team> firstPlace = new ArrayList<>();
        for (var e : session.placementByTeam.entrySet()) {
            if (e.getValue() != null && e.getValue() == 1 && e.getKey() != null) firstPlace.add(e.getKey());
        }
        if (firstPlace.size() != 1) return;

        Team winner = firstPlace.get(0);
        session.winningTeam = winner;
        session.result = "WIN:" + winner.name();

        plugin.getLogger().info("Matchbook: match " + session.matchId + " (arena=" + session.arenaName
                + ") was reported as a TIE, but only " + winner.name() + " finished 1st out of "
                + session.placementByTeam.size() + " teams — recording it as a win for " + winner.name()
                + ". This usually means MBedwars fired its winning-team event without a winner.");
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

    public void onIngameDeath(Arena arena, MatchSession session, Player victim, boolean fatalDeath,
                              boolean countingDeathStats, EntityDamageEvent.DamageCause cause,
                              MatchSession.DeathKey deathKey) {
        if (arena == null || victim == null || session == null) return;

        UUID uuid = victim.getUniqueId();
        session.unmarkSpectatorOnly(uuid);
        session.addParticipant(uuid);
        session.setUsername(uuid, victim.getName());
        Team team = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, team);

        // The kill event may already have written this death's full row (see onKill); one row per death.
        if (!session.consumeKillLoggedDeath(deathKey)) {
            long ts = now();
            MatchSession.PendingKill kill = session.consumePendingKill(uuid, ts);
            MatchEvent deathEvent = MatchEvent.playerDeath(ts, uuid.toString(), victim.getName(),
                    team != null ? team.name() : null, fatalDeath || (kill != null && kill.finalKill()),
                    cause != null ? cause.name() : null,
                    kill != null ? kill.killerUuid() : null,
                    kill != null ? kill.killerName() : null,
                    kill != null ? kill.killerTeam() : null,
                    kill != null ? kill.killCause() : null,
                    !countingDeathStats);
            session.addEvent(deathEvent);
            if (kill == null) session.registerDeathRow(deathKey, deathEvent);
        }

        if (!countingDeathStats) {
            // Row is logged either way; only the stat increment is skipped.
            if (fatalDeath && team != null) {
                session.markPlayerFinalDead(team, uuid);
                maybeMarkTeamEliminated(arena, session, team);
            }
            return;
        }

        session.addTriggerIncrement(uuid, "bedwars:deaths", 1L);
        if (fatalDeath) {
            session.addTriggerIncrement(uuid, "bedwars:final_deaths", 1L);

            // Placement tracking: fatal deaths remove the player from alive list.
            if (team != null) {
                session.markPlayerFinalDead(team, uuid);
                maybeMarkTeamEliminated(arena, session, team);
            }
        }
    }

    public void onKill(Arena arena, MatchSession session, Player killer, Player victim, boolean fatalDeath,
                       boolean countingKillStats, boolean countingDeathStats,
                       EntityDamageEvent.DamageCause killCause,
                       EntityDamageEvent.DamageCause victimDeathCause, MatchSession.DeathKey deathKey) {
        if (arena == null || killer == null || session == null || !countingKillStats) return;

        UUID uuid = killer.getUniqueId();
        session.unmarkSpectatorOnly(uuid);
        session.addParticipant(uuid);
        session.setUsername(uuid, killer.getName());
        Team killerTeam = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, killerTeam);

        session.addTriggerIncrement(uuid, "bedwars:kills", 1L);
        String victimName = victim != null ? victim.getName() : null;
        MatchSession.PendingKill attribution = new MatchSession.PendingKill(now(), uuid.toString(),
                killer.getName(), killerTeam != null ? killerTeam.name() : null, fatalDeath,
                killCause != null ? killCause.name() : null, victimName);
        if (victim == null) {
            // No victim handle to match a death row against — keep the legacy standalone row.
            session.addEvent(MatchEvent.playerKill(attribution.timestamp(), attribution.killerUuid(),
                    attribution.killerName(), attribution.killerTeam(), victimName, fatalDeath,
                    attribution.killCause()));
        } else if (deathKey == null) {
            // No shared Bukkit event to pair on — legacy victim+time matching.
            session.attributeKill(victim.getUniqueId(), attribution);
        } else if (!session.attributeKillByKey(deathKey, attribution)) {
            // Kill arrived first. PlayerKillPlayerEvent is a complete death record (it extends
            // PlayerIngameDeathEvent), so write the victim's PLAYER_DEATH row from it directly;
            // the death event skips row creation when it lands (consumeKillLoggedDeath).
            UUID victimUuid = victim.getUniqueId();
            session.addParticipant(victimUuid);
            session.setUsername(victimUuid, victim.getName());
            Team victimTeam = resolveTeamFromArena(arena, victimUuid);
            session.addEvent(MatchEvent.playerDeath(attribution.timestamp(), victimUuid.toString(),
                    victim.getName(), victimTeam != null ? victimTeam.name() : null, fatalDeath,
                    victimDeathCause != null ? victimDeathCause.name() : null,
                    attribution.killerUuid(), attribution.killerName(), attribution.killerTeam(),
                    attribution.killCause(), !countingDeathStats));
            session.markKillLoggedDeath(deathKey);
        }
        if (fatalDeath) {
            session.addTriggerIncrement(uuid, "bedwars:final_kills", 1L);
        }
    }

    /**
     * Called when MBedwars confirms a team has been fully eliminated.
     * This is the most reliable placement signal — it fires after both bed destruction
     * and all member deaths have been resolved, regardless of the order events arrive.
     */
    public void onTeamEliminate(Arena arena, MatchSession session, Team eliminatedTeam) {
        if (arena == null || eliminatedTeam == null || session == null) return;

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
        MatchSession session = liveSession(arena.getName());
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
        MatchSession session = liveSession(arena.getName());
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
        // A team assignment made while the round is running (auto-balance, admin reassignment)
        // confirms this player as part of this round — see classifyArenaJoin for why pre-round
        // assignments don't count.
        if (session.started) session.markRoundRoster(uuid);
        session.markTeamParticipating(newTeam);
        session.markPlayerAlive(newTeam, uuid);
        captureMatchStatsBaseline(uuid, session);
        cacheMatchId(uuid, arena.getName(), session.matchId);
    }

    public void onBedBreak(Arena arena, MatchSession session, Player breaker, Team bedTeam) {
        if (arena == null || breaker == null || session == null) return;

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
                    + "from that file. It is NOT listed by /mb all or openable with /mb view until you move "
                    + "it into a day folder under matches/ (YAML mode) or import it (MySQL mode): the failed/ "
                    + "folder is quarantine, so rejected records never masquerade as successfully stored ones.");
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
                recordMatchStats(session, uuid, snapshotFromGameStatsMap(mem.getGameStats()));
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

        takeGameSnapshots(remaining, snap -> recordMatchStats(session, snap.uuid, snap.snapshot), onComplete);
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
     * Captures this player's current game-stats reading as their matchStats baseline, if one hasn't
     * already been captured for this match (see {@link MatchSession#putMatchStatsBaselineIfAbsent}).
     * Called at every point a player becomes a real participant (round-start roster, join, or
     * mid-match team assignment) so the eventual final matchStats can be reported relative to 0
     * regardless of what MBedwars' own per-round counter held at that moment.
     */
    private void captureMatchStatsBaseline(UUID uuid, MatchSession session) {
        if (uuid == null || session == null) return;
        snapshotGameTrackedStats(uuid, snap -> session.putMatchStatsBaselineIfAbsent(uuid, snap));
    }

    /** Stores a raw game-stats reading as this player's final matchStats, netted against their baseline. */
    private void recordMatchStats(MatchSession session, UUID uuid, StatSnapshot raw) {
        if (session == null || uuid == null || raw == null) return;
        session.putMatchStats(uuid, baselineAdjust(session, uuid, raw));
    }

    /** Generation-guarded variant of {@link #recordMatchStats} for the async quit-time fallback path. */
    private void recordMatchStatsIfGeneration(MatchSession session, UUID uuid, StatSnapshot raw, long expectedGeneration) {
        if (session == null || uuid == null || raw == null) return;
        session.putMatchStatsIfGeneration(uuid, baselineAdjust(session, uuid, raw), expectedGeneration);
    }

    /**
     * Nets a raw game-stats reading against this player's captured baseline (see
     * matchStatsBaseline's javadoc in MatchSession) so the result is always relative to 0 for this
     * match. Falls back to the raw reading unchanged if no baseline was captured in time (e.g. an
     * extremely short round) — that matches the prior, un-baselined behavior rather than discarding
     * real data.
     */
    private StatSnapshot baselineAdjust(MatchSession session, UUID uuid, StatSnapshot raw) {
        StatSnapshot baseline = session.getMatchStatsBaseline(uuid);
        if (baseline == null) return raw;
        return new StatSnapshot(StatSnapshot.diff(baseline, raw));
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
     * Registers one online RoundEndEvent roster member as a participant of the ending match.
     *
     * Deliberately does NOT mark them on the round roster (see {@link MatchSession#markRoundRoster}):
     * this is MBedwars' account of who played, taken after the fact, and it is exactly the input that
     * has been observed to include people who weren't here. It fills in team/username for players we
     * already know about; anyone it introduces who left no trace in the round is dropped at
     * document-build time.
     */
    private void addRoundEndParticipant(Arena arena, MatchSession session, Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, p.getName());
        session.setTeam(uuid, resolveTeamFromArena(arena, uuid));
    }

    /**
     * Registers one already-quit RoundEndEvent roster member (QuitPlayerMemory) as a participant.
     *
     * Same caveat as {@link #addRoundEndParticipant}, and more so: the team here comes from the
     * memory itself rather than from live arena state, so a memory left over from an earlier round
     * on this arena arrives carrying a fully-formed team assignment. That is the phantom-team-member
     * path — it's tolerated here and filtered by the round-roster check at document-build time,
     * because for players who genuinely left mid-round this is the only source of their team.
     */
    private void addRoundEndQuitParticipant(MatchSession session, QuitPlayerMemory mem) {
        if (mem == null) return;
        UUID uuid = mem.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, mem.getUsername());
        session.setTeam(uuid, mem.getTeam());
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
