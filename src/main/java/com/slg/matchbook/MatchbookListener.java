package com.slg.matchbook;

import com.slg.matchbook.service.MatchLifecycleService;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaWinningTeamDetermineEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.arena.TeamEliminateEvent;
import de.marcely.bedwars.api.event.player.PlayerIngameDeathEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerTeamChangeEvent;
import de.marcely.bedwars.api.event.player.SpectatorJoinArenaEvent;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Bridges MBedwars events to the lifecycle service.
 */
public final class MatchbookListener implements Listener {

    private final MatchbookPlugin plugin;
    private final MatchLifecycleService lifecycle;

    public MatchbookListener(MatchbookPlugin plugin, MatchLifecycleService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoundStart(RoundStartEvent e) {
        lifecycle.onRoundStart(e.getArena());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoinArena(PlayerJoinArenaEvent e) {
        lifecycle.onPlayerJoinArena(e.getArena(), e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuitArena(PlayerQuitArenaEvent e) {
        lifecycle.onPlayerQuitArena(e.getArena(), e.getPlayer(), e.getReason());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWinningTeamDetermine(ArenaWinningTeamDetermineEvent e) {
        lifecycle.onArenaWinningTeam(e.getArena(), e.getWinningTeam());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoundEnd(RoundEndEvent e) {
        lifecycle.onRoundEnd(e.getArena());
    }

    /**
     * Direct placement signal: fires once a team's bed is gone and all its players are dead.
     * More reliable than deriving elimination from the bed-break + per-death chain alone.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeamEliminate(TeamEliminateEvent e) {
        lifecycle.onTeamEliminate(e.getArena(), e.getTeam());
    }

    /**
     * Reliable spectator classification.
     * LOSE/DEATH = eliminated participant becoming in-game spectator (still a real participant).
     * Anything else = external viewer who should not be counted in match stats.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpectatorJoin(SpectatorJoinArenaEvent e) {
        SpectateReason reason = e.getReason();
        if (reason == SpectateReason.LOSE || reason == SpectateReason.DEATH) return;
        lifecycle.onSpectatorJoinExternal(e.getArena(), e.getPlayer());
    }

    /**
     * Promotes pending players the moment MBedwars assigns them a team.
     * Fixes the race window where lobby-phase players had no team yet.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeamChange(PlayerTeamChangeEvent e) {
        Team newTeam = e.getNewTeam();
        if (newTeam == null) return; // team removed, not assigned
        lifecycle.onPlayerTeamAssigned(e.getArena(), e.getPlayer(), newTeam);
    }

    /**
     * Increment death counters reliably (covers void/fall/etc. too).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIngameDeath(PlayerIngameDeathEvent e) {
        if (e.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    lifecycle.onIngameDeath(e.getArena(), e.getPlayer(), e.isFatalDeath(), e.isCountingDeathStats()));
            return;
        }
        lifecycle.onIngameDeath(e.getArena(), e.getPlayer(), e.isFatalDeath(), e.isCountingDeathStats());
    }

    /**
     * Increment kill counters reliably.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerKillPlayerEvent e) {
        Player victim = tryGetVictim(e);
        if (e.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    lifecycle.onKill(e.getArena(), e.getKiller(), victim, e.isFatalDeath(), e.isCountingKillStats()));
            return;
        }
        lifecycle.onKill(e.getArena(), e.getKiller(), victim, e.isFatalDeath(), e.isCountingKillStats());
    }

    private static Player tryGetVictim(PlayerKillPlayerEvent e) {
        try { return e.getPlayer(); } catch (Throwable ignored) { return null; }
    }

    /**
     * Track bed breaks. MONITOR (not ignoreCancelled) so we catch the team even on cancelled breaks
     * for placement purposes — only the result=CANCEL check below gates stat recording.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedBreak(ArenaBedBreakEvent e) {
        if (e.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleBedBreak(e));
            return;
        }
        handleBedBreak(e);
    }

    private void handleBedBreak(ArenaBedBreakEvent e) {
        if (e.getResult() == ArenaBedBreakEvent.Result.CANCEL || !e.isPlayerCaused()) return;
        Player p = e.getPlayer();
        if (p == null) return;

        // Try direct API call first; fall back to reflection for older MBedwars builds.
        Team bedTeam = null;
        try {
            bedTeam = e.getTeam();
        } catch (Throwable ignored) {
            try {
                var m = e.getClass().getMethod("getTeam");
                Object o = m.invoke(e);
                if (o instanceof Team t) bedTeam = t;
            } catch (Throwable ignored2) {}
        }
        lifecycle.onBedBreak(e.getArena(), p, bedTeam);
    }

    /**
     * Used by PlaceholderAPI expansion.
     *
     * @return the active Matchbook matchId for the player's current arena, or empty string.
     */
    public String getMatchIdForPlayer(Player player) {
        if (player == null) return "";

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            arena = findArenaBySpectator(player);
        }
        if (arena != null) {
            MatchSession session = lifecycle.getOrCreateSession(arena, "placeholder");
            if (session != null) {
                lifecycle.cacheMatchId(player.getUniqueId(), arena.getName(), session.matchId);
                return session.matchId;
            }
        }

        return lifecycle.getCachedMatchId(player.getUniqueId());
    }

    private Arena findArenaBySpectator(Player player) {
        // Direct API method (MBedwars 5.x).
        try {
            Arena a = BedwarsAPI.getGameAPI().getArenaBySpectator(player);
            if (a != null) return a;
        } catch (Throwable ignored) {}

        // Fallback: iterate all arenas — handles edge cases and older builds.
        try {
            for (Arena a : BedwarsAPI.getGameAPI().getArenas()) {
                if (isSpectator(a, player)) return a;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean isSpectator(Arena arena, Player player) {
        try {
            var m = arena.getClass().getMethod("isSpectator", Player.class);
            Object o = m.invoke(arena, player);
            if (o instanceof Boolean b) return b;
        } catch (Throwable ignored) {}

        try {
            var m = arena.getClass().getMethod("getSpectators");
            Object o = m.invoke(arena);
            if (o instanceof java.util.Collection<?> c) return c.contains(player);
        } catch (Throwable ignored) {}

        return false;
    }
}
