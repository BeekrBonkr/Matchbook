package com.slg.matchbook;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MatchStorage {

    private final JavaPlugin plugin;

    public MatchStorage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public File getDayFolder(Date when) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd-yyyy");
        String day = fmt.format(when);

        new File(((MatchbookPlugin) plugin).getAddonDataFolder(), "matches")
        File folder = new File(base, day);
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Failed to create folder: " + folder.getAbsolutePath());
        }
        return folder;
    }

    /**
     * Filename:   <startUnix>-<md5(arenaName)>.yml
     * match_id:   last4(startUnix) + "-" + first4(md5 from filename)
     */
    public void saveMatchYaml(MatchSession session, String result) {
        long endUnix = session.endUnix != null ? session.endUnix : (System.currentTimeMillis() / 1000L);

        File dayFolder = getDayFolder(new Date(session.startUnix * 1000L));

        // md5 based ONLY on arena name (this is the md5 used in filename)
        String md5 = md5Hex(session.arenaName);

        // filename = unixtime-md5.yml
        String fileName = session.startUnix + "-" + md5 + ".yml";
        File outFile = new File(dayFolder, fileName);

        // match_id derived from unix + the SAME md5 used in the filename
        String matchId = matchIdFrom(session.startUnix, md5);

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("match.match_id", matchId);
        yml.set("match.start_unix", session.startUnix);
        yml.set("match.end_unix", endUnix);
        yml.set("match.arena", session.arenaName);
        yml.set("match.result", result);
        yml.set("match.start_snapshot_taken_unix", session.startSnapshotTakenUnix);

        List<String> participants = new ArrayList<>();
        for (UUID u : session.getParticipants()) participants.add(u.toString());
        yml.set("match.participants", participants);

        for (UUID u : session.getParticipants()) {
            String base = "players." + u;

            yml.set(base + ".username", session.getUsername(u));

            var team = session.getTeam(u);
            yml.set(base + ".team", team != null ? team.name() : null);

            yml.set(base + ".start_taken_unix", session.getStartTakenUnix(u));

            StatSnapshot start = session.getStart(u);
            StatSnapshot end = session.getEnd(u);

            if (start != null) yml.createSection(base + ".start", start.values());
            if (end != null) yml.createSection(base + ".end", end.values());

            if (start != null && end != null) {
                yml.createSection(base + ".diff", StatSnapshot.diff(start, end));
            }
        }

        try {
            yml.save(outFile);
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
            // fallback should be stable-ish but not cryptographic
            return Integer.toHexString(s.hashCode());
        }
    }
}
