package com.slg.matchbook;

import de.marcely.bedwars.api.BedwarsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MatchbookPlugin extends JavaPlugin {

    private MatchStorage storage;
    private MatchbookListener listener;

    public MatchbookListener getListener() {
        return listener;
    }

    @Override
    public void onEnable() {
        this.storage = new MatchStorage(this);

        // MBedwars API provides an onReady hook; use it so we don't race startup.
        BedwarsAPI.onReady(() -> {
            this.listener = new MatchbookListener(this, storage);
            Bukkit.getPluginManager().registerEvents(listener, this);

            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new com.slg.matchbook.placeholders.MatchbookExpansion(this).register();
                getLogger().info("Registered PlaceholderAPI expansion: matchbook");
            }

            getLogger().info("Matchbook enabled (MBedwars API ready).");
        });
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.flushAll("plugin-disable");
        }
        getLogger().info("Matchbook disabled.");
    }
}
