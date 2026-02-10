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
        YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchCode);
        if (yml == null) return null;

        File exportsDir = new File(plugin.getAddonDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory: " + exportsDir.getAbsolutePath());
        }

        File outFile = new File(exportsDir, matchCode + ".csv");

        List<String> columns = resolveColumns();

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {

            // Top: match codes used
            out.println("# match_codes: " + matchCode);

            out.println(String.join(",", headerForColumns(columns)));

            List<String> participants = yml.getStringList("match.participants");
            for (String uuidStr : participants) {
                String base = "players." + uuidStr;

                out.println(String.join(",", rowForColumns(columns, yml, uuidStr)));
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
        List<String> missing = new ArrayList<>();
        List<YamlConfiguration> matchYmls = new ArrayList<>();

        for (String code : matchCodes) {
            YamlConfiguration yml = plugin.getRepo().loadMatchYaml(code);
            if (yml == null) missing.add(code);
            else matchYmls.add(yml);
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

        List<String> columns = resolveColumns();
        List<String> statColumns = columns.stream().filter(MatchExporter::isStatKey).toList();

        // Aggregate by UUID
        Map<String, AggRow> rows = new LinkedHashMap<>();

        for (YamlConfiguration yml : matchYmls) {

            List<String> participants = yml.getStringList("match.participants");
            for (String uuidStr : participants) {
                String base = "players." + uuidStr;

                String username = yml.getString(base + ".username", "");
                String team = yml.getString(base + ".team", "");

                AggRow row = rows.computeIfAbsent(uuidStr, AggRow::new);

                if (username != null && !username.isBlank()) row.username = username;

                if (team != null && !team.isBlank()) {
                    if (row.team == null || row.team.isBlank()) row.team = team;
                    else if (!row.team.equalsIgnoreCase(team)) row.team = "MIXED";
                }

                for (String key : statColumns) {
                    long v = yml.getLong(base + ".diff." + key, 0L);
                    row.stats.merge(key, v, Long::sum);
                }
            }
        }

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {

            // Top: list match codes used
            out.println("# match_codes: " + String.join(", ", matchCodes));

            out.println(String.join(",", headerForColumns(columns)));

            for (AggRow r : rows.values()) out.println(String.join(",", rowForColumns(columns, r)));
        }

        return outFile;
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String cleaned = s.replace("\"", "\"\"");
        return needsQuotes ? ("\"" + cleaned + "\"") : cleaned;
    }

    private List<String> resolveColumns() {
        var settings = plugin.getSettings();
        List<String> fromConfig = settings != null && settings.export() != null ? settings.export().columns() : null;
        if (fromConfig != null && !fromConfig.isEmpty()) return fromConfig;

        // Smart default: uuid, username, team + tracked_keys
        List<String> out = new ArrayList<>();
        out.add("uuid");
        out.add("username");
        out.add("team");
        List<String> tracked = settings != null ? settings.trackedKeys() : plugin.getMatchbookConfig().trackedKeys();
        if (tracked != null) out.addAll(tracked);
        return out;
    }

    private static boolean isMeta(String col) {
        if (col == null) return false;
        String c = col.toLowerCase(Locale.ROOT);
        return c.equals("uuid") || c.equals("username") || c.equals("team");
    }

    private static boolean isStatKey(String col) {
        return col != null && !isMeta(col);
    }

    private static List<String> headerForColumns(List<String> columns) {
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            if (c == null) continue;
            String col = c.trim();
            if (col.isEmpty()) continue;
            if (isMeta(col)) {
                out.add(col.toLowerCase(Locale.ROOT));
            } else {
                out.add(simplifyStatKey(col));
            }
        }
        return out;
    }

    private static String simplifyStatKey(String key) {
        if (key == null) return "";
        int idx = key.indexOf(':');
        return idx >= 0 && idx + 1 < key.length() ? key.substring(idx + 1) : key;
    }

    private static List<String> rowForColumns(List<String> columns, YamlConfiguration yml, String uuidStr) {
        String base = "players." + uuidStr;
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            if (c == null) continue;
            String col = c.trim();
            if (col.isEmpty()) continue;
            switch (col.toLowerCase(Locale.ROOT)) {
                case "uuid" -> out.add(csv(uuidStr));
                case "username" -> out.add(csv(yml.getString(base + ".username", "")));
                case "team" -> out.add(csv(yml.getString(base + ".team", "")));
                default -> {
                    long v = yml.getLong(base + ".diff." + col, 0L);
                    out.add(Long.toString(v));
                }
            }
        }
        return out;
    }

    private static List<String> rowForColumns(List<String> columns, AggRow r) {
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            if (c == null) continue;
            String col = c.trim();
            if (col.isEmpty()) continue;
            switch (col.toLowerCase(Locale.ROOT)) {
                case "uuid" -> out.add(csv(r.uuid));
                case "username" -> out.add(csv(r.username != null ? r.username : ""));
                case "team" -> out.add(csv(r.team != null ? r.team : ""));
                default -> out.add(Long.toString(r.stats.getOrDefault(col, 0L)));
            }
        }
        return out;
    }

    private static final class AggRow {
        final String uuid;

        String username;
        String team;

        final Map<String, Long> stats = new LinkedHashMap<>();

        AggRow(String uuid) {
            this.uuid = uuid;
        }
    }
}
