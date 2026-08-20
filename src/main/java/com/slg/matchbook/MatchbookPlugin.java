package com.slg.matchbook;

import com.slg.matchbook.commands.MatchbookCommand;
import com.slg.matchbook.config.RuntimeSettings;
import com.slg.matchbook.config.StorageType;
import com.slg.matchbook.gui.EventLogGui;
import com.slg.matchbook.gui.MatchesDetailsGui;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.placeholders.MatchbookExpansion;
import com.slg.matchbook.storage.HealthCheckResult;
import com.slg.matchbook.storage.MatchRepository;
import com.slg.matchbook.storage.YamlMatchRepository;
import com.slg.matchbook.storage.MySqlMatchRepository;
import com.slg.matchbook.service.MatchLifecycleService;
import com.slg.matchbook.service.TimezoneService;
import com.slg.matchbook.service.UpdateChecker;
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
    private volatile MatchRepository repo;
    private volatile boolean storageReconnecting = false;

    private MatchLifecycleService lifecycle;
    private MatchbookListener listener;
    private MatchesDetailsGui detailsGui;
    private MatchesGui matchesGui;
    private EventLogGui eventLogGui;
    private UpdateChecker updateChecker;
    private TimezoneService timezones;

    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public TimezoneService getTimezones()   { return timezones; }

    public MatchesGui getMatchesGui()       { return matchesGui;   }
    public MatchesDetailsGui getDetailsGui(){ return detailsGui;   }
    public EventLogGui getEventLogGui()     { return eventLogGui;  }

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
     * Reloads config and runtime settings, and — if the storage section actually changed — hot-swaps
     * the storage backend without needing a server restart. Safe to call from the main thread.
     *
     * @return true if a storage config change was detected and a reconnect was triggered (the
     *         reconnect itself runs asynchronously; check the console or {@code /mb test} shortly
     *         after for the outcome).
     */
    public boolean reloadMatchbook() {
        String oldStorageFingerprint = storageFingerprint();

        this.config.load();
        this.settings = config.runtimeSettings();

        boolean storageChanged = !oldStorageFingerprint.equals(storageFingerprint());
        if (storageChanged) reconnectStorage();
        return storageChanged;
    }

    /** Cheap "did the storage config change" signal — not a real hash, just an equality check. */
    private String storageFingerprint() {
        var section = config.raw().getConfigurationSection("storage");
        return section != null ? String.valueOf(section.getValues(true)) : "";
    }

    /**
     * Builds and validates a brand-new storage backend off the main thread, and only swaps it in for
     * {@link #repo} if it initializes AND passes a health check — a bad config change (typo'd MySQL
     * credentials, unreachable host, etc.) can't take down a previously-working storage backend. The
     * old backend is only shut down after the swap, once nothing can reference it anymore.
     */
    private void reconnectStorage() {
        if (storageReconnecting) {
            getLogger().warning("Matchbook: storage reconnect already in progress; ignoring duplicate trigger.");
            return;
        }
        storageReconnecting = true;

        StorageType newType = config.storageType();
        getLogger().info("Matchbook: storage config changed — reconnecting (" + newType + ")...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            MatchRepository candidate = newType == StorageType.MYSQL
                    ? new MySqlMatchRepository(this, config.raw())
                    : new YamlMatchRepository(this);

            String failure = null;
            try {
                candidate.init();
                HealthCheckResult health = candidate.healthCheck();
                if (!health.ok()) failure = health.message();
            } catch (Exception e) {
                failure = e.getMessage();
            }

            String finalFailure = failure;
            Bukkit.getScheduler().runTask(this, () -> {
                storageReconnecting = false;

                if (finalFailure != null) {
                    getLogger().severe("Matchbook: storage reconnect failed (" + newType + "): " + finalFailure
                            + " — keeping the previous storage backend active. Fix config.yml and run /mb reload again.");
                    try {
                        candidate.shutdown();
                    } catch (Exception e) {
                        getLogger().warning("Matchbook: error cleaning up failed reconnect attempt: " + e.getMessage());
                    }
                    return;
                }

                MatchRepository old = this.repo;
                this.repo = candidate;
                getLogger().info("Matchbook: storage backend reconnected successfully (" + newType + ").");

                if (old != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        try {
                            old.shutdown();
                        } catch (Exception e) {
                            getLogger().warning("Matchbook: error shutting down previous storage backend: " + e.getMessage());
                        }
                    });
                }
            });
        });
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
        this.config.load();
        this.settings = config.runtimeSettings();
        this.timezones = new TimezoneService(this);

        StorageType type = config.storageType();
        if (type == StorageType.MYSQL) this.repo = new MySqlMatchRepository(this, config.raw());
        else this.repo = new YamlMatchRepository(this);

        try {
            this.repo.init();
        } catch (Exception e) {
            getLogger().severe("Matchbook: Storage init failed (" + type + "). Falling back to YAML. " + e.getMessage());

            // Shut the failed backend down before dropping the reference. MySqlMatchRepository#init
            // builds its HikariCP pool before running schema DDL, so a failure in that DDL (bad
            // permissions, unreachable mid-init) leaves a live pool holding connections for the rest
            // of the server's uptime with nothing left pointing at it. reconnectStorage() already
            // cleans up a rejected candidate this way; this path didn't.
            try {
                this.repo.shutdown();
            } catch (Throwable t) {
                getLogger().warning("Matchbook: error cleaning up the failed storage backend: " + t.getMessage());
            }

            this.repo = new YamlMatchRepository(this);
            try {
                this.repo.init();
            } catch (Exception e2) {
                getLogger().severe("Matchbook: YAML fallback storage also failed to initialize: " + e2.getMessage()
                        + " — Matchbook will not be able to save or read matches until this is fixed.");
            }
        }

        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.start();

        // Registered unconditionally (unlike MatchbookListener) so operators still get caught up
        // on an available update even on a hub/lobby server, which never registers MatchbookListener.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                if (updateChecker != null) updateChecker.notifyIfOp(e.getPlayer());
            }
        }, this);

        BedwarsAPI.onReady(() -> {
            boolean hubMode = config.hubMode();
            if (hubMode) {
                getLogger().info("Matchbook: Hub/lobby mode enabled — match recording is disabled. "
                        + "This server will only read/export from storage.");
            } else {
                this.lifecycle = new MatchLifecycleService(this);
                this.listener = new MatchbookListener(this, lifecycle);
                Bukkit.getPluginManager().registerEvents(listener, this);
            }

            // GUI
            this.detailsGui  = new MatchesDetailsGui(this);
            this.matchesGui  = new MatchesGui(this, detailsGui);
            this.eventLogGui = new EventLogGui(this);

            Bukkit.getPluginManager().registerEvents(detailsGui,  this);
            Bukkit.getPluginManager().registerEvents(matchesGui,  this);
            Bukkit.getPluginManager().registerEvents(eventLogGui, this);
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
        // Save synchronously and BEFORE closing the repo: the scheduler refuses async tasks once the
        // plugin is marked disabled (which happens before onDisable() runs), so anything scheduled here
        // would throw immediately and silently drop in-progress matches.
        if (lifecycle != null) lifecycle.flushAll("plugin-disable", true);

        if (repo != null) repo.shutdown();
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
