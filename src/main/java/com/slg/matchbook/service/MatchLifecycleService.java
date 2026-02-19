package com.slg.matchbook.service;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.StatSnapshot;
import com.slg.matchbook.model.MatchDocument;
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
import org.bukkit.scheduler.BukkitRunnable;

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

    public MatchLifecycleService(MatchbookPlugin plugin) {
        this.plugin = plugin;
        // MBedwars API is accessed via static entrypoints (no singleton instance).
        this.playerDataApi = BedwarsAPI.getPlayerDataAPI();
    }

    public MatchSession getSession(String arenaName) {
        return sessionsByArena.get(arenaName);
    }

    public void onRoundStart(Arena arena) {
        if (arena == null) return;

        // If a session already exists (created early on first join), reuse its matchId.
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) {
            // Short human-friendly code for esports submissions
            String matchId = MatchIdUtil.newMatchId();
            long startUnix = System.currentTimeMillis() / 1000L;
            session = new MatchSession(matchId, arena.getName(), startUnix);
            sessionsByArena.put(arena.getName(), session);
        }

        // Overwrite start time at the true round start.
        session.startUnix = System.currentTimeMillis() / 1000L;

        // Participants at start
        for (Player p : arena.getPlayers()) {
            session.addParticipant(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
            Team team = resolveTeamFromArena(arena, p.getUniqueId());
            session.setTeam(p.getUniqueId(), team);
            if (team != null) {
                session.markTeamParticipating(team);
                session.markPlayerAlive(team, p.getUniqueId());
            }
        }

        // Take totals snapshot near start for auditing/debugging.
        takeStartSnapshots(arena, session);

        // Watchdog in case MBedwars doesn't fire RoundEnd.
        startAbortWatchdog(arena, session);
    }

    public void onPlayerJoinArena(Arena arena, Player player) {
        if (arena == null || player == null) return;

        // Create a session early so placeholders can resolve during pre-round phases.
        MatchSession session = sessionsByArena.computeIfAbsent(arena.getName(), __ ->
                new MatchSession(MatchIdUtil.newMatchId(), arena.getName(), System.currentTimeMillis() / 1000L));

        UUID uuid = player.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, player.getName());

        Team team = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, team);

        // Placement tracking: mark participation + alive status for this player.
        if (team != null) {
            session.markTeamParticipating(team);
            session.markPlayerAlive(team, uuid);
        }

        // If the player previously quit this same arena (and we captured quit stats),
        // but they rejoined, drop that captured snapshot so we store the final result.
        session.removeMatchStats(uuid);
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
        session.addParticipant(uuid);
        session.setUsername(uuid, player.getName());
        Team team = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, team);

        // If they quit mid-match, treat as no longer alive for placement purposes.
        // (This matches esports expectations: leaving = eliminated / not alive.)
        if (team != null) session.markPlayerFinalDead(team, uuid);

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
            // Fallback: try to read live game stats right now (safe at quit-time).
            snapshotGameTrackedStats(uuid, snap -> session.putMatchStats(uuid, snap));
        }
    }

    public void onRoundEnd(Arena arena) {
        if (arena == null) return;

        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        session.endUnix = System.currentTimeMillis() / 1000L;

        // Add any remaining online players (still in arena)
        for (Player p : arena.getPlayers()) {
            session.addParticipant(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
            session.setTeam(p.getUniqueId(), resolveTeamFromArena(arena, p.getUniqueId()));
        }

        final String result = session.result != null
                ? session.result
                : (session.winningTeam != null ? ("WIN:" + session.winningTeam.name()) : "UNKNOWN");

        long delay = plugin.getMatchbookConfig().runtimeSettings().endSnapshotDelayTicks();

        // Capture totals end snapshot (debug/audit), then capture per-match stats and save.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            takeEndSnapshots(arena, session, () -> {
                captureMatchStatsFromArena(arena, session, () -> {
                    // Ensure critical counters are present even if snapshot sources missed them.
                    session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);

                    // Finalize placement keys into the per-player matchStats map.
                    finalizePlacements(arena, session);
                    session.applyPlacementsToMatchStats();

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
        int place = Math.max(1, totalTeams - eliminatedIndex + 1);
        session.setPlacementIfAbsent(team, place);
    }

    private void finalizePlacements(Arena arena, MatchSession session) {
        if (arena == null || session == null) return;

        // Make sure we have a team roster based on participants we've seen.
        for (UUID u : session.getParticipants()) {
            Team t = session.getTeam(u);
            if (t != null) session.markTeamParticipating(t);
        }

        // Winner => 1st place.
        if (session.winningTeam != null) {
            session.setPlacementIfAbsent(session.winningTeam, 1);
        }

        // Any team without placement (e.g., ties/aborts) gets 1st if still "alive", otherwise best-effort.
        for (Team t : session.participatingTeams) {
            if (session.placementByTeam.containsKey(t)) continue;

            boolean bedLost = session.bedLostTeams.contains(t);
            boolean fullyDead = session.isTeamFullyDead(t);

            if (!bedLost && !fullyDead) {
                // Still alive at end -> treat as 1st (tie) unless a winner already assigned.
                session.setPlacementIfAbsent(t, session.winningTeam != null ? 2 : 1);
            } else if (bedLost && fullyDead) {
                // Should have been caught, but just in case.
                maybeMarkTeamEliminated(arena, session, t);
            } else {
                // Partial info; default to last known place bucket.
                int fallback = Math.max(1, session.totalTeams());
                session.setPlacementIfAbsent(t, fallback);
            }
        }
    }

    // ----------------------------------------------------------------------
    // Critical stat events (kills/deaths/beds)
    // ----------------------------------------------------------------------

    public void onIngameDeath(Arena arena, Player victim, boolean fatalDeath, boolean countingDeathStats) {
        if (arena == null || victim == null || !countingDeathStats) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = victim.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, victim.getName());
        Team team = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, team);

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

    public void onKill(Arena arena, Player killer, boolean fatalDeath, boolean countingKillStats) {
        if (arena == null || killer == null || !countingKillStats) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = killer.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, killer.getName());
        session.setTeam(uuid, resolveTeamFromArena(arena, uuid));

        session.addTriggerIncrement(uuid, "bedwars:kills", 1L);
        if (fatalDeath) {
            session.addTriggerIncrement(uuid, "bedwars:final_kills", 1L);
        }
    }

    public void onBedBreak(Arena arena, Player breaker, Team bedTeam) {
        if (arena == null || breaker == null) return;
        MatchSession session = sessionsByArena.get(arena.getName());
        if (session == null) return;

        UUID uuid = breaker.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, breaker.getName());
        Team breakerTeam = resolveTeamFromArena(arena, uuid);
        session.setTeam(uuid, breakerTeam);

        session.addTriggerIncrement(uuid, "bedwars:beds_destroyed", 1L);

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
        if (reason != null) plugin.getLogger().warning("Matchbook: flushing matches due to " + reason);
        Map<String, MatchSession> copy = new LinkedHashMap<>(sessionsByArena);
        sessionsByArena.clear();

        for (MatchSession session : copy.values()) {
            session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);

            String result = session.result != null ? session.result : "ABORTED";
            MatchDocument doc = MatchDocument.fromSession(session, result);

            if (!shouldPersist(session, doc)) continue;

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getRepo().saveMatch(doc);
                } catch (Exception e) {
                    plugin.getLogger().severe("Matchbook: failed to save match " + doc.matchId() + " : " + e.getMessage());
                }
            });
        }
    }

    // -----------------
    // Internals
    // -----------------

    private void finishMatch(Arena arena, MatchSession session, String result) {
        // Remove the session now (we have what we need). This prevents late events from touching it.
        sessionsByArena.remove(arena.getName());

        session.applyTriggerIncrementsToMatchStats(PERSIST_TRIGGER_KEYS);
        MatchDocument doc = MatchDocument.fromSession(session, result);

        // Requirement: only save matches if kills/deaths/bed breaks happened.
        if (!shouldPersist(session, doc)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getRepo().saveMatch(doc);
            } catch (Exception e) {
                plugin.getLogger().severe("Matchbook: failed to save match " + doc.matchId() + " : " + e.getMessage());
            }
        });
    }

    private boolean shouldPersist(MatchSession session, MatchDocument doc) {
        // Prefer event-driven signal, but fall back to inspecting the document.
        if (session != null && session.hasTriggerActivity()) return true;
        return hasAnyNonZeroDiffForKeys(doc, PERSIST_TRIGGER_KEYS);
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

                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            try {
                                plugin.getRepo().saveMatch(doc);
                            } catch (Exception e) {
                                plugin.getLogger().severe("Matchbook: failed to save aborted match " + doc.matchId() + " : " + e.getMessage());
                            }
                        });
                    });

                    sessionsByArena.remove(arena.getName());
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

    private static final class UuidSnapshot {
        final UUID uuid;
        final StatSnapshot snapshot;

        UuidSnapshot(UUID uuid, StatSnapshot snapshot) {
            this.uuid = uuid;
            this.snapshot = snapshot;
        }
    }
}
