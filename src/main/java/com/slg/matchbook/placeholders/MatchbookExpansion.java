package com.slg.matchbook.placeholders;

import com.slg.matchbook.MatchbookPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public final class MatchbookExpansion extends PlaceholderExpansion {

    private final MatchbookPlugin plugin;

    public MatchbookExpansion(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "matchbook";
    }

    @Override
    public String getAuthor() {
        return "SLG";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive /papi reload
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) return "";
        if (plugin.getListener() == null) return "";

        // Keep OLD placeholders working:
        //   %matchbook_match_code%
        //   %matchbook_match_id%
        //
        // Also support a nicer canonical one:
        //   %matchbook_matchid%
        if (params.equalsIgnoreCase("match_code")
                || params.equalsIgnoreCase("match_id")
                || params.equalsIgnoreCase("matchid")) {
            return plugin.getListener().getMatchIdForPlayer(player);
        }

        return "";
    }
}
