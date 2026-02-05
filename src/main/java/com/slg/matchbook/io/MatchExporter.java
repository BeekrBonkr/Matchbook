package com.slg.matchbook.io;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MatchExporter {

    private final JavaPlugin plugin;

    public MatchExporter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Finds a match by match.match_id and writes a CSV to:
     *   <pluginDataFolder>/exports/<matchCode>.csv
     *
     * @return output File if exported, or null if match not found
     */
    public File exportMatchToCsv(String matchCode) throws IOException {
        File matchFile = findMatchFileByCode(matchCode);
        if (matchFile == null) return null;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(matchFile);

        File exportsDir = new File(plugin.getDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory: " + exportsDir.getAbsolutePath());
        }

        File outFile = new File(exportsDir, matchCode + ".csv");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {

            // Header / match info
            String arena = yml.getString("match.arena", "");
            long startUnix = yml.getLong("match.start_unix", 0L);
            long endUnix = yml.getLong("match.end_unix", 0L);
            String result = yml.getString("match.result", "");

            out.println("# Matchbook Export");
            out.println("# match_id," + csv(matchCode));
            out.println("# arena," + csv(arena));
            out.println("# result," + csv(result));
            out.println("# start_unix," + startUnix);
            out.println("# end_unix," + endUnix);
            out.println("# start_time," + csv(formatUnix(startUnix)));
            out.println("# end_time," + csv(formatUnix(endUnix)));
            out.println();

            // Column header (human-readable)
            out.println(String.join(",",
                    "uuid",
                    "username",
                    "team",
                    "kills",
                    "final_kills",
                    "final_deaths",
                    "beds_destroyed",
                    "wins",
                    "loses"
            ));

            // Participants list (stable order)
            List<String> participants = yml.getStringList("match.participants");
            for (String uuidStr : participants) {
                String base = "players." + uuidStr;

                String username = yml.getString(base + ".username", "");
                String team = yml.getString(base + ".team", "");

                // Use DIFF values (per-match)
                long kills = yml.getLong(base + ".diff.bedwars:kills", 0L);
                long fk = yml.getLong(base + ".diff.bedwars:final_kills", 0L);
                long fd = yml.getLong(base + ".diff.bedwars:final_deaths", 0L);
                long beds = yml.getLong(base + ".diff.bedwars:beds_destroyed", 0L);
                long wins = yml.getLong(base + ".diff.bedwars:wins", 0L);
                long loses = yml.getLong(base + ".diff.bedwars:loses", 0L);

                out.println(String.join(",",
                        csv(uuidStr),
                        csv(username),
                        csv(team),
                        Long.toString(kills),
                        Long.toString(fk),
                        Long.toString(fd),
                        Long.toString(beds),
                        Long.toString(wins),
                        Long.toString(loses)
                ));
            }
        }

        return outFile;
    }

    /**
     * Searches all day folders under <dataFolder>/matches for a yaml file whose match.match_id equals matchCode.
     */
    private File findMatchFileByCode(String matchCode) {
        File matchesDir = new File(plugin.getDataFolder(), "matches");
        if (!matchesDir.exists() || !matchesDir.isDirectory()) return null;

        File[] dayDirs = matchesDir.listFiles(File::isDirectory);
        if (dayDirs == null) return null;

        for (File dayDir : dayDirs) {
            File[] files = dayDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;

            for (File f : files) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String id = yml.getString("match.match_id", "");
                if (matchCode.equalsIgnoreCase(id)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String formatUnix(long unix) {
        if (unix <= 0) return "";
        Date d = new Date(unix * 1000L);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    // Basic CSV escaping
    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String cleaned = s.replace("\"", "\"\"");
        return needsQuotes ? ("\"" + cleaned + "\"") : cleaned;
    }
}
