package com.slg.matchbook;

import com.slg.matchbook.commands.MatchbookCommand;
import com.slg.matchbook.config.RuntimeSettings;
import com.slg.matchbook.config.StorageType;
import com.slg.matchbook.gui.MatchesDetailsGui;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.placeholders.MatchbookExpansion;
import com.slg.matchbook.storage.MatchRepository;
import com.slg.matchbook.service.MatchLifecycleService;
import com.slg.matchbook.service.PartyFollowService;
import de.marcely.bedwars.api.BedwarsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class MatchbookPlugin extends JavaPlugin {

    public MatchRepository getRepo() {
        return repo;
    }

    private com.slg.matchbook.config.MatchbookConfig config;
    private volatile RuntimeSettings settings;
    private MatchRepository repo;

    private MatchStorage storage;
    private MatchLifecycleService lifecycle;
    private MatchbookListener listener;
        private MatchesDetailsGui detailsGui;
        private MatchesGui matchesGui;

        public MatchesGui getMatchesGui() { return matchesGui; }
        public MatchesDetailsGui getDetailsGui() { return detailsGui; }

    private File addonDataFolder;

    // GUI instance

    public MatchbookListener getListener() {
        return listener;
    }

    public com.slg.matchbook.config.MatchbookConfig getMatchbookConfig() {
        return config;
    }

    public RuntimeSettings getSettings() {
        return settings;
    }

    /**
     * Reloads config and runtime settings. Safe to call from main thread.
     */
    public void reloadMatchbook() {
        this.config.load();
        this.settings = config.runtimeSettings();
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

        this.config = new com.slg.matchbook.config.MatchbookConfig(this);
        reloadMatchbook();

        StorageType type = config.storageType();
        if (type == StorageType.MYSQL) this.repo = new com.slg.matchbook.storage.MySqlMatchRepository(this, config.raw());
        else this.repo = new com.slg.matchbook.storage.YamlMatchRepository(this);

        try {
            this.repo.init();
        } catch (Exception e) {
            getLogger().severe("Matchbook: Storage init failed (" + type + "). Falling back to YAML. " + e.getMessage());
            this.repo = new com.slg.matchbook.storage.YamlMatchRepository(this);
            try { this.repo.init(); } catch (Exception ignored) {}
        }

        this.storage = new MatchStorage(this);

        BedwarsAPI.onReady(() -> {
            this.lifecycle = new MatchLifecycleService(this);
            PartyFollowService partyFollow = new PartyFollowService(this);
            this.listener = new MatchbookListener(this, lifecycle, partyFollow);
            Bukkit.getPluginManager().registerEvents(listener, this);

            // GUI
            this.detailsGui = new MatchesDetailsGui(this);
            this.matchesGui = new MatchesGui(this, detailsGui);

            Bukkit.getPluginManager().registerEvents(detailsGui, this);
            Bukkit.getPluginManager().registerEvents(matchesGui, this);
            // Command
            if (getCommand("matchbook") != null) {
                MatchbookCommand cmd = new MatchbookCommand(this);
                getCommand("matchbook").setExecutor(cmd);
                getCommand("matchbook").setTabCompleter(cmd);
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
        if (repo != null) repo.shutdown();

        if (lifecycle != null) lifecycle.flushAll("plugin-disable");
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
