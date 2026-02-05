package com.slg.matchbook;

import com.slg.matchbook.commands.MatchbookCommand;
import com.slg.matchbook.gui.MatchesDetailsGui;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.placeholders.MatchbookExpansion;
import de.marcely.bedwars.api.BedwarsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class MatchbookPlugin extends JavaPlugin {

    private MatchStorage storage;
    private MatchbookListener listener;
        private MatchesDetailsGui detailsGui;
        private MatchesGui matchesGui;

        public MatchesGui getMatchesGui() { return matchesGui; }

    private File addonDataFolder;

    // GUI instance

    public MatchbookListener getListener() {
        return listener;
    }

    public File getAddonDataFolder() {
        return addonDataFolder != null ? addonDataFolder : getDataFolder();
    }

    @Override
    public void onEnable() {
        this.addonDataFolder = resolveAddonDataFolder();
        if (!addonDataFolder.exists() && !addonDataFolder.mkdirs()) {
            getLogger().warning("Matchbook: Failed to create data folder: " + addonDataFolder.getAbsolutePath());
        }

        this.storage = new MatchStorage(this);

        BedwarsAPI.onReady(() -> {
            this.listener = new MatchbookListener(this, storage);
            Bukkit.getPluginManager().registerEvents(listener, this);

            // GUI
            this.detailsGui = new MatchesDetailsGui(this);
            this.matchesGui = new MatchesGui(this, detailsGui);

            Bukkit.getPluginManager().registerEvents(detailsGui, this);
            Bukkit.getPluginManager().registerEvents(matchesGui, this);
            // Command
            if (getCommand("matchbook") != null) {
                getCommand("matchbook").setExecutor(new MatchbookCommand(this));
            } else {
                getLogger().warning("Matchbook: command 'matchbook' not found in plugin.yml");
            }

            // PlaceholderAPI
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new MatchbookExpansion(this).register();
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

    /**
     * Hard-pin to: plugins/MBedwars/add-ons/Matchbook
     * (Don't derive from CodeSource, Paper remaps plugins.)
     */
    private File resolveAddonDataFolder() {
        File pluginsDir = getServer().getPluginsFolder();
        File addonsDir = new File(new File(pluginsDir, "MBedwars"), "add-ons");
        File data = new File(addonsDir, "Matchbook");

        getLogger().info("Matchbook: Plugins folder = " + pluginsDir.getAbsolutePath());
        getLogger().info("Matchbook: MBedwars add-ons = " + addonsDir.getAbsolutePath());
        getLogger().info("Matchbook: Using data folder = " + data.getAbsolutePath());

        return data;
    }
}
