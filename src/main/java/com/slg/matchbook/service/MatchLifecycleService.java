package com.slg.matchbook.service;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.StatSnapshot;
import com.slg.matchbook.config.RuntimeSettings;
import com.slg.matchbook.io.MatchYamlCodec;
import com.slg.matchbook.model.MatchDocument;
import com.slg.matchbook.util.MatchIdUtil;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.player.PlayerDataAPI;
import de.marcely.bedwars.api.player.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns the match session lifecycle (start -> snapshots -> end -> save).
 *
 * Listener should be thin and delegate into this class.
 */
public final class MatchLifecycleService {

    private final MatchbookPlugin plugin;
    private final PlayerDataAPI playerDataApi;
    private final Map<String, MatchSession> sessionsByArena = new ConcurrentHashMap<>();

    public MatchLifecycleService(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.playerDataApi = BedwarsAPI.getPlayerDataAPI();
    }

    public MatchSession getSession(Arena arena) {
        if (arena == null) return null;
        return sessionsByArena.get(arena.getName());
    }

    public Collection<MatchSession> getSessions() {
        return sessionsByArena.values();
    }

    public String getMatchIdForPlayer(Player player) {
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        for (MatchSession s : sessionsByArena.values()) {
            if (s != null && s.getParticipants().contains(uuid)) {
                return s.matchId != null ? s.matchId : "";
            }
        }
        return "";
    }

    public void onRoundStart(Arena arena) {
        if (arena == null) return;

        long startUnix = System.currentTimeMillis() / 1000L;
        String matchId = MatchIdUtil.newMatchId();
        MatchSession session = new MatchSession(matchId, arena.getName(), startUnix);

        sessionsByArena.put(arena.getName(), session);

        for (Player p : arena.getPlayers()) {
            session.addParticipant(p.getUniqueId());
            session.setUsername(p.getUniqueId(), p.getName());
        }

        // Start snapshot after arena transitions to RUNNING.
        waitUntilRunningThenSnapshotStart(arena, session, 0);

        // Abort watchdog: if arena returns to WAITING/STOPPED without a RoundEnd, record an aborted match.
        startAbortWatchdog(arena, session);
    }

    public void onPlayerJoinArena(Arena arena, Player player, Team team) {
        MatchSession session = getSession(arena);
        if (session == null || player == null) return;

        UUID uuid = player.getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, player.getName());
        if (team != null) session.setTeam(uuid, team);
    }

    public void onWinningTeamDetermine(Arena arena, boolean tie, Team winningTeam) {
        MatchSession session = getSession(arena);
        if (session == null) return;

        if (tie || winningTeam == null) {
            session.winningTeam = null;
            session.result = "TIE";
        } else {
            session.winningTeam = winningTeam;
            session.result = "WIN:" + winningTeam.name();
        }
    }

    public void onRoundEnd(Arena arena, boolean tie, Collection<? extends Player> winners) {
        MatchSession session = getSession(arena);
        if (session == null) return;

        session.endUnix = System.currentTimeMillis() / 1000L;
        for (Player p : arena.getPlayers()) session.addParticipant(p.getUniqueId());

        String computed = session.result;
        if (computed == null) {
            if (tie) {
                computed = "TIE";
            } else {
                Team winTeam = session.winningTeam;
                if (winTeam == null && winners != null && !winners.isEmpty()) {
                    UUID winUuid = winners.iterator().next().getUniqueId();
                    winTeam = session.getTeam(winUuid);
                    if (winTeam == null) winTeam = resolveTeamFromArena(arena, winUuid);
                }
                computed = "WIN:" + (winTeam != null ? winTeam.name() : "UNKNOWN");
                session.winningTeam = winTeam;
            }
            session.result = computed;
        }

        final String result = computed;

        RuntimeSettings settings = plugin.getSettings();
        long endDelay = settings != null ? settings.endSnapshotDelayTicks() : 80L;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Refresh usernames/teams
            for (UUID u : session.getParticipants()) {
                Player online = Bukkit.getPlayer(u);
                if (online != null) session.setUsername(u, online.getName());
                Team t = resolveTeamFromArena(arena, u);
                if (t != null) session.setTeam(u, t);
            }

            snapshotMany(session.getParticipants(), snap -> session.putEnd(snap.uuid, snap.snapshot), () -> {
                // Build doc on main thread, then save async
                MatchDocument doc = MatchDocument.fromSession(session, result);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.getRepo().saveMatch(doc);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Matchbook: failed to save matchId="
                                + doc.matchId() + " arena=" + doc.arenaName() + " result=" + result + " : " + e.getMessage());
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sessionsByArena.remove(arena.getName());
                        plugin.getLogger().info("Matchbook: saved arena=" + arena.getName()
                                + " participants=" + session.getParticipants().size()
                                + " result=" + result);
                    });
                });
            });
        }, endDelay);
    }

    public void flushAll(String reason) {
        plugin.getLogger().warning("Matchbook: flushAll (" + reason + ") saving partial sessions.");

        List<MatchSession> snapshot = new ArrayList<>(sessionsByArena.values());
        sessionsByArena.clear();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (MatchSession session : snapshot) {
                try {
                    MatchDocument doc = MatchDocument.fromSession(session, "ABORTED");
                    plugin.getRepo().saveMatch(doc);
                } catch (Exception e) {
                    plugin.getLogger().severe("Matchbook: flushAll failed to save matchId="
                            + (session != null ? session.matchId : "null")
                            + " arena=" + (session != null ? session.arenaName : "null")
                            + " : " + e.getMessage());
                }
            }
        });
    }

    private void waitUntilRunningThenSnapshotStart(Arena arena, MatchSession session, int attempts) {
        RuntimeSettings settings = plugin.getSettings();
        int max = settings != null ? settings.runningWaitTicksMax() : 100;

        if (attempts > max) {
            plugin.getLogger().warning("Matchbook: arena never became RUNNING in time; snapshotting start anyway. arena=" + arena.getName());
            Bukkit.getScheduler().runTaskLater(plugin, () -> takeStartSnapshots(arena, session), 1L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sessionsByArena.containsKey(arena.getName())) return;

            if (arena.getStatus() == ArenaStatus.RUNNING) {
                long delay = settings != null ? settings.startSnapshotDelayTicks() : 20L;
                Bukkit.getScheduler().runTaskLater(plugin, () -> takeStartSnapshots(arena, session), delay);
            } else {
                waitUntilRunningThenSnapshotStart(arena, session, attempts + 1);
            }
        }, 1L);
    }

    private void takeStartSnapshots(Arena arena, MatchSession session) {
        for (Player p : arena.getPlayers()) session.addParticipant(p.getUniqueId());
        session.startSnapshotTakenUnix = System.currentTimeMillis() / 1000L;

        for (UUID u : session.getParticipants()) {
            Player online = Bukkit.getPlayer(u);
            if (online != null) session.setUsername(u, online.getName());
            else {
                OfflinePlayer off = Bukkit.getOfflinePlayer(u);
                if (off != null) session.setUsername(u, off.getName());
            }

            Team team = resolveTeamFromArena(arena, u);
            if (team != null) session.setTeam(u, team);

            session.setStartTakenUnix(u, session.startSnapshotTakenUnix);
        }

        snapshotMany(session.getParticipants(), snap -> session.putStart(snap.uuid, snap.snapshot), () -> {
            plugin.getLogger().info("Matchbook: start snapshots captured arena=" + arena.getName()
                    + " participants=" + session.getParticipants().size()
                    + " at=" + session.startSnapshotTakenUnix);
        });
    }

    private void snapshotMany(Set<UUID> uuids, Consumer<UuidSnapshot> consumer, Runnable onComplete) {
        if (uuids == null || uuids.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(uuids.size());
        AtomicBoolean finished = new AtomicBoolean(false);

        for (UUID uuid : uuids) {
            snapshotTrackedStats(uuid, statSnap -> {
                // PlayerDataAPI callbacks may be async. Keep ALL session mutation and completion on the server thread.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    consumer.accept(new UuidSnapshot(uuid, statSnap));

                    if (remaining.decrementAndGet() == 0) {
                        if (onComplete != null && finished.compareAndSet(false, true)) {
                            onComplete.run();
                        }
                    }
                });
            });
        }

        RuntimeSettings settings = plugin.getSettings();
        long timeout = settings != null ? settings.snapshotTimeoutTicks() : 80L;

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

    private void startAbortWatchdog(Arena arena, MatchSession session) {
        // Poll arena status; if the session is still present but arena is no longer running/starting, consider it aborted.
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            MatchSession current = sessionsByArena.get(arena.getName());
            if (current == null || current != session) {
                task.cancel();
                return;
            }

            ArenaStatus status = arena.getStatus();
            if (status == ArenaStatus.RUNNING) return;

            // If match ended normally we remove session in onRoundEnd save callback.
            // Here, if arena is no longer RUNNING but we never got RoundEnd, save ABORTED.
            // (Some MBedwars versions don't expose a STARTING state; treat any non-RUNNING as abort once a session exists.)
            if (current.endUnix == null) {
                task.cancel();
                plugin.getLogger().warning("Matchbook: match aborted (no RoundEnd). arena=" + arena.getName() + " status=" + status);
                flushSingle(arena, current, "ABORTED");
            }
        }, 20L, 20L);
    }

    private void flushSingle(Arena arena, MatchSession session, String result) {
        sessionsByArena.remove(arena.getName());
        MatchDocument doc = MatchDocument.fromSession(session, result);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getRepo().saveMatch(doc);
            } catch (Exception e) {
                plugin.getLogger().severe("Matchbook: failed to save aborted match " + doc.matchId() + " : " + e.getMessage());
            }
        });
    }

    /**
     * Resolve a player's team by checking Arena membership.
     */
    private Team resolveTeamFromArena(Arena arena, UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return null;

        for (Team t : Team.values()) {
            try {
                if (arena.getPlayersInTeam(t).contains(p)) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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
