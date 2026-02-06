package com.slg.matchbook;

import com.slg.matchbook.io.MatchYamlCodec;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MatchStorage {

    private final MatchbookPlugin plugin;

    public MatchStorage(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    public File getDayFolder(Date when) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd-yyyy");
        String day = fmt.format(when);

        File base = new File(plugin.getAddonDataFolder(), "matches");
        File folder = new File(base, day);

        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Failed to create folder: " + folder.getAbsolutePath());
        }
        return folder;
    }

    public void saveMatchYaml(MatchSession session, String result) {
        File dayFolder = getDayFolder(new Date(session.startUnix * 1000L));

        String md5 = md5Hex(session.arenaName);
        String fileName = session.startUnix + "-" + md5 + ".yml";
        File outFile = new File(dayFolder, fileName);

        YamlConfiguration yml = MatchYamlCodec.toYaml(session, result);

        try {
            yml.save(outFile);
            // Build a RELATIVE path like: "02-05-2026/<filename>.yml"
            String relative = outFile.getParentFile().getName() + "/" + outFile.getName();

            UserMatchIndex index = new UserMatchIndex(plugin);
            for (UUID u : session.getParticipants()) {
                index.addMatchForPlayer(u, session.matchId, relative);
            }

            plugin.getLogger().info("Matchbook: wrote match file: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save match file " + outFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public static String matchIdFrom(long startUnix, String md5FromFilename) {
        String unixStr = Long.toString(startUnix);
        String last4 = unixStr.length() <= 4 ? unixStr : unixStr.substring(unixStr.length() - 4);
        String first4 = md5FromFilename.length() <= 4 ? md5FromFilename : md5FromFilename.substring(0, 4);
        return last4 + "-" + first4;
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
