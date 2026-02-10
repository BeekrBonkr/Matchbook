package com.slg.matchbook;

import com.slg.matchbook.service.MatchLifecycleService;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.arena.ArenaWinningTeamDetermineEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Thin event layer for MBedwars. All real work lives in {@link MatchLifecycleService}.
 */
public final class MatchbookListener implements Listener {

    private final MatchbookPlugin plugin;
    private final MatchLifecycleService lifecycle;

    public MatchbookListener(MatchbookPlugin plugin, MatchLifecycleService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    public MatchSession getSession(Arena arena) {
        return lifecycle.getSession(arena);
    }

    /**
     * Placeholder / public API.
     */
    public String getMatchIdForPlayer(Player player) {
        return lifecycle.getMatchIdForPlayer(player);
    }

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        lifecycle.onRoundStart(event.getArena());
    }

    @EventHandler
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        lifecycle.onPlayerJoinArena(event.getArena(), event.getPlayer(), event.getTeam());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWinningTeamDetermine(ArenaWinningTeamDetermineEvent event) {
        lifecycle.onWinningTeamDetermine(event.getArena(), event.isTie(), event.getWinningTeam());
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        lifecycle.onRoundEnd(event.getArena(), event.isTie(), event.getWinners());
    }

    public void flushAll(String reason) {
        lifecycle.flushAll(reason);
    }
}
