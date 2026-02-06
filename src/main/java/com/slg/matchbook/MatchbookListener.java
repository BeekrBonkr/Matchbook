package com.slg.matchbook;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaWinningTeamDetermineEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.player.PlayerDataAPI;
import de.marcely.bedwars.api.player.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MatchbookListener implements Listener {

    private final MatchbookPlugin plugin;
    private final MatchStorage storage;

    private final Map<Arena, MatchSession> sessions = new ConcurrentHashMap<>();

    private static final int RUNNING_WAIT_TICKS_MAX = 100;     // 5 seconds
    private static final long START_SNAPSHOT_DELAY_TICKS = 20; // 1 second after RUNNING
    private static final long END_SNAPSHOT_DELAY_TICKS = 80;   // 4 seconds after RoundEnd
    private static final long SNAPSHOT_TIMEOUT_TICKS = 80L;    // 4 seconds

    public MatchbookListener(MatchbookPlugin plugin, MatchStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public MatchSession getSession(Arena arena) {
        return sessions.get(arena);
    }

    /**
     * Placeholder / public API:
     * Returns current match code for the player if they are in an arena with an active session, else "".
     */
    public String getMatchIdForPlayer(Player player) {
        if (player == null) return "";

        for (MatchSession session : sessions.values()) {
            if (session == null) continue;
            if (session.getParticipants().contains(player.getUniqueId())) {
                return session.matchId != null ? session.matchId : "";
            }
        }
        return "";
    }

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        Arena arena = event.getArena();
        long startUnix = System.currentTimeMillis() / 1000L;

        String matchId = com.slg.matchbook.util.MatchIdUtil.newMatchId();
        MatchSession session = new MatchSession(matchId, arena.getName(), startUnix);

        sessions.put(arena, session);

        for (Player p : arena.getPlayers()) session.addParticipant(p.getUniqueId());

        waitUntilRunningThenSnapshotStart(arena, session, 0);
    }

    @EventHandler
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        MatchSession session = sessions.get(event.getArena());
        if (session == null) return;

        UUID uuid = event.getPlayer().getUniqueId();
        session.addParticipant(uuid);
        session.setUsername(uuid, event.getPlayer().getName());

        Team t = event.getTeam();
        if (t != null) session.setTeam(uuid, t);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWinningTeamDetermine(ArenaWinningTeamDetermineEvent event) {
        MatchSession session = sessions.get(event.getArena());
        if (session == null) return;

        if (event.isTie() || event.getWinningTeam() == null) {
            session.winningTeam = null;
            session.result = "TIE";
        } else {
            session.winningTeam = event.getWinningTeam();
            session.result = "WIN:" + event.getWinningTeam().name();
        }
    }

    private void waitUntilRunningThenSnapshotStart(Arena arena, MatchSession session, int attempts) {
        if (attempts > RUNNING_WAIT_TICKS_MAX) {
            plugin.getLogger().warning("Matchbook: arena never became RUNNING in time; snapshotting start anyway. arena=" + arena.getName());
            Bukkit.getScheduler().runTaskLater(plugin, () -> takeStartSnapshots(arena, session), 1L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sessions.containsKey(arena)) return;

            if (arena.getStatus() == ArenaStatus.RUNNING) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> takeStartSnapshots(arena, session), START_SNAPSHOT_DELAY_TICKS);
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

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        MatchSession session = sessions.get(arena);
        if (session == null) return;

        session.endUnix = System.currentTimeMillis() / 1000L;

        for (Player p : arena.getPlayers()) session.addParticipant(p.getUniqueId());

        String computed = session.result;
        if (computed == null) {
            if (event.isTie()) {
                computed = "TIE";
            } else {
                Team winTeam = session.winningTeam;
                if (winTeam == null && !event.getWinners().isEmpty()) {
                    UUID winUuid = event.getWinners().iterator().next().getUniqueId();
                    winTeam = session.getTeam(winUuid);
                    if (winTeam == null) winTeam = resolveTeamFromArena(arena, winUuid);
                }
                computed = "WIN:" + (winTeam != null ? winTeam.name() : "UNKNOWN");
                session.winningTeam = winTeam;
            }
            session.result = computed;
        }

        final String result = computed;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : session.getParticipants()) {
                Player online = Bukkit.getPlayer(u);
                if (online != null) session.setUsername(u, online.getName());
                Team team = resolveTeamFromArena(arena, u);
                if (team != null) session.setTeam(u, team);
            }

            snapshotMany(session.getParticipants(), snap -> session.putEnd(snap.uuid, snap.snapshot), () -> {
                try {
                    plugin.getRepo().saveMatch(session, result);
                } catch (Exception e) {
                    plugin.getLogger().severe("Matchbook: failed to save matchId="
                            + (session != null ? session.matchId : "null")
                            + " arena=" + (session != null ? session.arenaName : "null")
                            + " result=" + result
                            + " : " + e.getMessage());
                }
                sessions.remove(arena);
                plugin.getLogger().info("Matchbook: saved arena=" + arena.getName()
                        + " participants=" + session.getParticipants().size()
                        + " result=" + result);
            });
        }, END_SNAPSHOT_DELAY_TICKS);
    }

    private void snapshotMany(Set<UUID> uuids, Consumer<UuidSnapshot> consumer, Runnable onComplete) {
        if (uuids == null || uuids.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(uuids.size());
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);

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

        if (onComplete != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int left = remaining.get();
                if (left > 0 && finished.compareAndSet(false, true)) {
                    plugin.getLogger().warning("Matchbook: snapshot timeout; completing with partial data. remaining=" + left);
                    onComplete.run();
                }
            }, SNAPSHOT_TIMEOUT_TICKS);
        }
    }

    private void snapshotTrackedStats(UUID uuid, Consumer<StatSnapshot> consumer) {
        PlayerDataAPI api = BedwarsAPI.getPlayerDataAPI();

        api.getStats(uuid, stats -> {
            PlayerStats chosen = pickBestStats(stats);
            if (chosen == null) {
                consumer.accept(StatSnapshot.empty());
                return;
            }

            Map<String, Long> out = new LinkedHashMap<>();
            for (String key : StatSnapshot.TRACKED_KEYS) {
                Number n = chosen.get(key);
                out.put(key, n == null ? 0L : n.longValue());
            }

            consumer.accept(new StatSnapshot(out));
        });
    }

    private PlayerStats pickBestStats(Object statsObj) {
        Object overall = invokeFirst(statsObj, new String[]{"getStats", "getOverallStats", "getGlobalStats"});
        if (overall instanceof PlayerStats ps) return ps;

        Object game = invokeFirst(statsObj, new String[]{"getGameStats", "getCurrentGameStats"});
        if (game instanceof PlayerStats ps) return ps;

        return null;
    }

    private Object invokeFirst(Object target, String[] methodNames) {
        for (String name : methodNames) {
            try {
                var m = target.getClass().getMethod(name);
                Object out = m.invoke(target);
                if (out != null) return out;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Resolve a player's team by checking Arena membership.
     * MBedwars Team is a color enum; membership is tracked on Arena.
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

    public void flushAll(String reason) {
        plugin.getLogger().warning("Matchbook: flushAll (" + reason + ") saving partial sessions.");

        // Snapshot to avoid concurrent modification issues
        List<MatchSession> snapshot = new ArrayList<>(sessions.values());

        for (MatchSession session : snapshot) {
            try {
                // Use selected storage backend (yaml or mysql)
                plugin.getRepo().saveMatch(session, "ABORTED");
            } catch (Exception e) {
                plugin.getLogger().severe("Matchbook: flushAll failed to save matchId="
                        + (session != null ? session.matchId : "null")
                        + " arena=" + (session != null ? session.arenaName : "null")
                        + " : " + e.getMessage());
            }
        }

        sessions.clear();
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
