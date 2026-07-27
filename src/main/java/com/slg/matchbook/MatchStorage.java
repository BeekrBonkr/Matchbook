package com.slg.matchbook;

import com.slg.matchbook.io.MatchYamlCodec;
import com.slg.matchbook.model.MatchDocument;
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

    /**
     * Writes the match file and updates each participant's history index.
     *
     * Throws on write failure rather than logging and returning: the caller
     * ({@link com.slg.matchbook.storage.YamlMatchRepository#saveMatch}) is wrapped by the
     * retry-then-write-a-recovery-copy safety net in MatchLifecycleService#persistMatch, and that net
     * only engages on an exception. Swallowing the IOException here made a disk-full / permissions /
     * bad-path failure lose the match outright — no retry, no recovery copy, one log line — which is
     * exactly the case the net exists for.
     */
    public void saveMatchYaml(MatchDocument doc) throws IOException {
        File dayFolder = getDayFolder(new Date(doc.startUnix() * 1000L));

        String md5 = md5Hex(doc.arenaName());
        // Include the matchId: startUnix+arena alone can collide (two rounds of the same arena
        // starting within the same second, e.g. after a fast restart), which would silently
        // overwrite the earlier match's file. Nothing parses this filename — lookups read
        // match.match_id from the file contents — so this is safe for existing installs.
        String fileName = doc.startUnix() + "-" + md5 + "-" + doc.matchId() + ".yml";
        File outFile = new File(dayFolder, fileName);

        YamlConfiguration yml = MatchYamlCodec.toYaml(doc);
        yml.save(outFile);

        plugin.getLogger().info("Matchbook: wrote match file: " + outFile.getAbsolutePath());

        // Index updates are best-effort and deliberately NOT part of the save's success contract:
        // the match document itself is already safely on disk, and failing the save here would send
        // it through the retry path and rewrite a file that's already correct.
        // Build a RELATIVE path like: "02-05-2026/<filename>.yml"
        String relative = outFile.getParentFile().getName() + "/" + outFile.getName();
        UserMatchIndex index = new UserMatchIndex(plugin);
        for (UUID u : doc.participants()) {
            index.addMatchForPlayer(u, doc.matchId(), relative);
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
