package com.slg.matchbook.io;

import com.slg.matchbook.MatchbookPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MatchExporter {

    private final MatchbookPlugin plugin;

    public MatchExporter(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    private static String sanitizeFileName(String name) {
        // Replace anything risky for filenames (spaces, slashes, colon, etc.)
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


    /**
     * Single match export -> exports/<matchCode>.csv
     */
    public File exportMatchToCsv(String matchCode) throws IOException {
        File matchFile = findMatchFileByCode(matchCode);
        if (matchFile == null) return null;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(matchFile);

        File exportsDir = new File(plugin.getAddonDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory: " + exportsDir.getAbsolutePath());
        }

        File outFile = new File(exportsDir, matchCode + ".csv");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {

            // Top: match codes used
            out.println("# match_codes: " + matchCode);

            // Header exactly as requested
            out.println("uuid,username,team,kills,final_kills,final_deaths,beds_destroyed,wins,loses");

            List<String> participants = yml.getStringList("match.participants");
            for (String uuidStr : participants) {
                String base = "players." + uuidStr;

                String username = yml.getString(base + ".username", "");
                String team = yml.getString(base + ".team", "");

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
     * Multi-match combined export -> exports/combined-<timestamp>.csv
     * Sums rows by UUID (stats added together).
     *
     * At top: list match codes used.
     */
    public File exportMatchesCombinedToCsv(List<String> matchCodes) throws IOException {
        if (matchCodes == null || matchCodes.isEmpty()) {
            throw new IllegalArgumentException("matchCodes is empty");
        }

        // Find all files first (and tell caller if any are missing)
        List<File> matchFiles = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String code : matchCodes) {
            File f = findMatchFileByCode(code);
            if (f == null) missing.add(code);
            else matchFiles.add(f);
        }

        if (!missing.isEmpty()) {
            throw new IOException("Match not found: " + String.join(", ", missing));
        }

        File exportsDir = new File(plugin.getAddonDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory: " + exportsDir.getAbsolutePath());
        }

        String safeName = String.join("_", matchCodes);
        safeName = sanitizeFileName(safeName);

        // keep filenames reasonable on Windows/Linux; trim if needed
        if (safeName.length() > 180) {
            safeName = safeName.substring(0, 180);
        }

        File outFile = new File(exportsDir, safeName + ".csv");

        // Aggregate by UUID
        Map<String, AggRow> rows = new LinkedHashMap<>();

        for (File matchFile : matchFiles) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(matchFile);

            List<String> participants = yml.getStringList("match.participants");
            for (String uuidStr : participants) {
                String base = "players." + uuidStr;

                String username = yml.getString(base + ".username", "");
                String team = yml.getString(base + ".team", "");

                long kills = yml.getLong(base + ".diff.bedwars:kills", 0L);
                long fk = yml.getLong(base + ".diff.bedwars:final_kills", 0L);
                long fd = yml.getLong(base + ".diff.bedwars:final_deaths", 0L);
                long beds = yml.getLong(base + ".diff.bedwars:beds_destroyed", 0L);
                long wins = yml.getLong(base + ".diff.bedwars:wins", 0L);
                long loses = yml.getLong(base + ".diff.bedwars:loses", 0L);

                AggRow row = rows.computeIfAbsent(uuidStr, AggRow::new);

                // username: keep latest non-empty seen
                if (username != null && !username.isBlank()) row.username = username;

                // team: if multiple different non-empty teams appear, mark MIXED
                if (team != null && !team.isBlank()) {
                    if (row.team == null || row.team.isBlank()) row.team = team;
                    else if (!row.team.equalsIgnoreCase(team)) row.team = "MIXED";
                }

                row.kills += kills;
                row.finalKills += fk;
                row.finalDeaths += fd;
                row.bedsDestroyed += beds;
                row.wins += wins;
                row.loses += loses;
            }
        }

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {

            // Top: list match codes used
            out.println("# match_codes: " + String.join(", ", matchCodes));

            // Header exactly as requested
            out.println("uuid,username,team,kills,final_kills,final_deaths,beds_destroyed,wins,loses");

            for (AggRow r : rows.values()) {
                out.println(String.join(",",
                        csv(r.uuid),
                        csv(r.username != null ? r.username : ""),
                        csv(r.team != null ? r.team : ""),
                        Long.toString(r.kills),
                        Long.toString(r.finalKills),
                        Long.toString(r.finalDeaths),
                        Long.toString(r.bedsDestroyed),
                        Long.toString(r.wins),
                        Long.toString(r.loses)
                ));
            }
        }

        return outFile;
    }

    /**
     * Searches all day folders under <addonDataFolder>/matches for a yaml whose match.match_id equals matchCode.
     */
    private File findMatchFileByCode(String matchCode) {
        File matchesDir = new File(plugin.getAddonDataFolder(), "matches");
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

    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String cleaned = s.replace("\"", "\"\"");
        return needsQuotes ? ("\"" + cleaned + "\"") : cleaned;
    }

    private static final class AggRow {
        final String uuid;

        String username;
        String team;

        long kills;
        long finalKills;
        long finalDeaths;
        long bedsDestroyed;
        long wins;
        long loses;

        AggRow(String uuid) {
            this.uuid = uuid;
        }
    }
}
