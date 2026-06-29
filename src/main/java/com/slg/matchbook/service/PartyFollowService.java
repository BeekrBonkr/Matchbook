package com.slg.matchbook.service;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.config.RuntimeSettings;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.AddPlayerCause;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.hook.HookAPI;
import de.marcely.bedwars.api.hook.PartiesHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Moves party members into the party leader's lobby arena when the leader joins.
 *
 * Requires a parties plugin hooked into MBedwars (e.g. Party+Friends).
 * Gated behind the party.follow_leader_to_arena config key.
 */
public final class PartyFollowService {

    private final MatchbookPlugin plugin;

    public PartyFollowService(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Call this when a player joins a lobby arena.
     * If the player is a party leader and the feature is enabled, their party members
     * are moved into the same arena after a configurable delay.
     */
    public void onPlayerJoinLobby(Arena arena, Player player) {
        RuntimeSettings settings = plugin.getSettings();
        if (settings == null || settings.party() == null) return;
        if (!settings.party().followLeaderToArena()) return;

        PartiesHook[] hooks = getPartiesHooks();
        if (hooks == null || hooks.length == 0) return;

        tryHook(hooks, 0, arena, player);
    }

    // Try each registered parties hook in order; stop once one resolves the player's party.
    private void tryHook(PartiesHook[] hooks, int index, Arena arena, Player player) {
        if (index >= hooks.length) return;

        hooks[index].getMember(player, optMember -> {
            if (optMember == null || !optMember.isPresent()) {
                tryHook(hooks, index + 1, arena, player);
                return;
            }

            PartiesHook.Member self = optMember.get();
            if (!self.isLeader()) return;

            PartiesHook.Party party = self.getParty();
            if (party == null) return;

            RuntimeSettings s = plugin.getSettings();
            long delay = (s != null && s.party() != null) ? Math.max(1L, s.party().followDelayTicks()) : 5L;

            // Schedule on the main thread after the delay so arena.addPlayer is always
            // called synchronously and we can re-check arena state.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (arena.getStatus() != ArenaStatus.LOBBY) return;

                Collection<PartiesHook.Member> members;
                try {
                    // true = include all members (leaders + regular).
                    members = party.getMembers(true);
                } catch (Throwable e) {
                    return;
                }

                UUID leaderUuid = player.getUniqueId();
                for (PartiesHook.Member m : members) {
                    if (m == null) continue;
                    UUID uuid = m.getUniqueId();
                    if (uuid == null || uuid.equals(leaderUuid)) continue;

                    Player memberPlayer = Bukkit.getPlayer(uuid);
                    if (memberPlayer == null || !memberPlayer.isOnline()) continue;

                    // Don't pull members who are already in any arena.
                    if (BedwarsAPI.getGameAPI().getArenaByPlayer(memberPlayer) != null) continue;

                    try {
                        arena.addPlayer(memberPlayer, null, AddPlayerCause.PARTY_SWITCH_ARENA);
                    } catch (Throwable ex) {
                        if (plugin.getMatchbookConfig().debugLogging()) {
                            plugin.getLogger().warning("Matchbook: party follow failed for "
                                    + memberPlayer.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }, delay);
        });
    }

    private PartiesHook[] getPartiesHooks() {
        try {
            HookAPI hookApi = BedwarsAPI.getHookAPI();
            return hookApi != null ? hookApi.getPartiesHooks() : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
