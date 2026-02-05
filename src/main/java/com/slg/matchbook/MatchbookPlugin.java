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

    /**
     * Resolve: <jar_dir>/Matchbook
     * If jar is in plugins/MBedwars/add-ons/, data becomes:
     *   plugins/MBedwars/add-ons/Matchbook/
     */
    private File resolveAddonDataFolder() {
        try {
            URI uri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File jarFile = new File(uri);
            File jarDir = jarFile.getParentFile(); // should be .../plugins/MBedwars/add-ons
            if (jarDir != null && jarDir.isDirectory()) {
                return new File(jarDir, "Matchbook");
            }
        } catch (Exception ignored) {
        }

        // fallback: default Bukkit folder
        return getDataFolder();
    }
}
