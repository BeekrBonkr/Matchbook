package com.slg.matchbook;

import com.slg.matchbook.service.MatchLifecycleService;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaWinningTeamDetermineEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.player.PlayerIngameDeathEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
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
        Arena arena = e.getArena();
        lifecycle.onRoundStart(arena);
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
        // Winner may be null on tie.
        lifecycle.onArenaWinningTeam(e.getArena(), e.getWinningTeam());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoundEnd(RoundEndEvent e) {
        lifecycle.onRoundEnd(e.getArena());
    }

    /**
     * Increment death counters reliably (covers void/fall/etc. too).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIngameDeath(PlayerIngameDeathEvent e) {
        // Some platforms may fire async; keep lifecycle state changes on the main thread.
        if (e.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () -> lifecycle.onIngameDeath(e.getArena(), e.getPlayer(), e.isFatalDeath(), e.isCountingDeathStats()));
            return;
        }
        lifecycle.onIngameDeath(e.getArena(), e.getPlayer(), e.isFatalDeath(), e.isCountingDeathStats());
    }

    /**
     * Increment kill counters reliably.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerKillPlayerEvent e) {
        if (e.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, () -> lifecycle.onKill(e.getArena(), e.getKiller(), e.isFatalDeath(), e.isCountingKillStats()));
            return;
        }
        lifecycle.onKill(e.getArena(), e.getKiller(), e.isFatalDeath(), e.isCountingKillStats());
    }

    /**
     * Track bed breaks.
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
        if (e.getResult() != ArenaBedBreakEvent.Result.CANCEL && e.isPlayerCaused()) {
            Player p = e.getPlayer();
            if (p != null) {
                lifecycle.onBedBreak(e.getArena(), p);
            }
        }
    }

    /**
     * Used by PlaceholderAPI expansion.
     *
     * @return the active Matchbook matchId for the player's current arena, or empty string.
     */
    public String getMatchIdForPlayer(Player player) {
        if (player == null) return "";

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return "";

        MatchSession session = lifecycle.getSession(arena.getName());
        return session != null ? session.matchId : "";
    }
}
