package com.slg.matchbook;

import de.marcely.bedwars.api.BedwarsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;

public final class MatchbookPlugin extends JavaPlugin {

    private MatchStorage storage;
    private MatchbookListener listener;

    // NEW: custom data folder under MBedwars/add-ons/
    private File addonDataFolder;

    public MatchbookListener getListener() {
        return listener;
    }

    public File getAddonDataFolder() {
        return addonDataFolder != null ? addonDataFolder : getDataFolder();
    }

    @Override
    public void onEnable() {
        this.addonDataFolder = resolveAddonDataFolder();
        if (!addonDataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            addonDataFolder.mkdirs();
        }

        this.storage = new MatchStorage(this);

        BedwarsAPI.onReady(() -> {
            this.listener = new MatchbookListener(this, storage);
            Bukkit.getPluginManager().registerEvents(listener, this);

            getCommand("matchbook").setExecutor(new com.slg.matchbook.commands.MatchbookCommand(this));

            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new com.slg.matchbook.placeholders.MatchbookExpansion(this).register();
                getLogger().info("Registered PlaceholderAPI expansion: matchbook");
            }

            getLogger().info("Matchbook enabled. Data folder: " + getAddonDataFolder().getAbsolutePath());
        });
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.flushAll("plugin-disable");
        }
    }

    private File resolveAddonDataFolder() {
        File pluginsDir = getServer().getPluginsFolder(); // .../plugins
        File addonsDir = new File(new File(pluginsDir, "MBedwars"), "add-ons");

        // If MBedwars uses a different casing on disk sometimes, you can add a second fallback,
        // but start with the canonical one.
        File data = new File(addonsDir, "Matchbook");

        getLogger().info("Matchbook: Plugins folder = " + pluginsDir.getAbsolutePath());
        getLogger().info("Matchbook: MBedwars add-ons = " + addonsDir.getAbsolutePath());
        getLogger().info("Matchbook: Using data folder = " + data.getAbsolutePath());

        return data;
    }

}
