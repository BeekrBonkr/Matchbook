package com.slg.matchbook.io;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.io.MatchYamlCodec;
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

        File outFile = new File(exportsDir, sanitizeFileName(matchCode) + ".csv");

        List<String> columns = resolveColumnsWithPlacements(List.of(yml));

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

        List<String> columns = resolveColumnsWithPlacements(matchYmls);
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
        // Defuse CSV/formula injection: a cell starting with =, +, -, or @ is executed as a formula
        // by Excel/Sheets when the file is opened. Prefixing with a single quote forces text mode.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
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

    /**
     * Adds any detected placement columns (matchbook:*_place) from the selected matches.
     * This ensures multi-match export sums placement counts correctly without requiring
     * admins to keep export column configs in sync.
     */
    private List<String> resolveColumnsWithPlacements(List<YamlConfiguration> matchYmls) {
        List<String> base = new ArrayList<>(resolveColumns());

        // Discover matchbook-generated keys (placements + ties) across all matches being exported.
        Set<String> placementKeys = new HashSet<>();
        boolean hasTies = false;
        if (matchYmls != null) {
            for (YamlConfiguration yml : matchYmls) {
                if (yml == null) continue;
                for (String uuidStr : yml.getStringList("match.participants")) {
                    var sec = yml.getConfigurationSection("players." + uuidStr + ".diff");
                    if (sec == null) continue;
                    for (String k : sec.getKeys(false)) {
                        if (k == null) continue;
                        if (k.startsWith("matchbook:") && k.endsWith("_place")) placementKeys.add(k);
                        if (k.equals("matchbook:ties")) hasTies = true;
                    }
                }
            }
        }

        if (placementKeys.isEmpty() && !hasTies) return base;

        // Sort placement keys by numeric rank (1st,2nd,3rd,4th...)
        List<String> sorted = new ArrayList<>(placementKeys);
        sorted.sort(Comparator.comparingInt(MatchExporter::placementKeyRank));

        // Insert matchbook columns after username if possible, otherwise append.
        int insertAt = -1;
        for (int i = 0; i < base.size(); i++) {
            if ("username".equalsIgnoreCase(base.get(i))) {
                insertAt = i + 1;
                break;
            }
        }
        if (insertAt < 0) insertAt = base.size();

        for (String k : sorted) {
            if (!base.contains(k)) base.add(insertAt++, k);
        }
        if (hasTies && !base.contains("matchbook:ties")) base.add(insertAt, "matchbook:ties");
        return base;
    }

    private static int placementKeyRank(String key) {
        if (key == null) return Integer.MAX_VALUE;
        String s = key;
        int idx = s.indexOf(':');
        if (idx >= 0) s = s.substring(idx + 1);
        if (s.endsWith("_place")) s = s.substring(0, s.length() - "_place".length());
        // s is like "1st" or "12th"
        String digits = s.replaceAll("\\D+", "");
        if (digits.isEmpty()) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
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

    // -----------------------------------------------------------------------
    // Event log export
    // -----------------------------------------------------------------------

    /** Exports events for a single match to exports/<matchCode>_events.csv. Returns null if no events. */
    public File exportMatchEventsToCsv(String matchCode) throws IOException {
        YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchCode);
        if (yml == null) return null;

        List<Map<String, Object>> events = MatchYamlCodec.readRawEvents(yml);
        if (events.isEmpty()) return null;

        File exportsDir = new File(plugin.getAddonDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory");
        }

        File outFile = new File(exportsDir, sanitizeFileName(matchCode) + "_events.csv");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {

            out.println("# match: " + matchCode);
            out.println("offset_seconds,wall_clock_unix,type,player_name,player_uuid,player_team,"
                    + "killer_name,killer_uuid,killer_team,bed_team,final,cause,was_spectating");

            for (Map<String, Object> ev : events) {
                out.println(String.join(",", eventRow(ev)));
            }
        }

        return outFile;
    }

    /** Exports events for multiple matches into a single chronologically sorted events CSV. */
    public File exportCombinedEventsToCsv(List<String> matchCodes) throws IOException {
        if (matchCodes == null || matchCodes.isEmpty()) return null;

        List<Map<String, Object>> allEvents = new ArrayList<>();
        for (String code : matchCodes) {
            YamlConfiguration yml = plugin.getRepo().loadMatchYaml(code);
            if (yml == null) continue;
            List<Map<String, Object>> evs = MatchYamlCodec.readRawEvents(yml);
            // Tag each event with its match code for the combined output
            for (Map<String, Object> ev : evs) {
                Map<String, Object> tagged = new LinkedHashMap<>(ev);
                tagged.put("_match", code);
                allEvents.add(tagged);
            }
        }

        if (allEvents.isEmpty()) return null;

        // Sort chronologically by timestamp
        allEvents.sort(Comparator.comparingLong(m -> {
            Object v = m.get("timestamp");
            return v instanceof Number n ? n.longValue() : 0L;
        }));

        File exportsDir = new File(plugin.getAddonDataFolder(), "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Could not create exports directory");
        }

        String name = sanitizeFileName(String.join("_", matchCodes));
        if (name.length() > 160) name = name.substring(0, 160);
        File outFile = new File(exportsDir, name + "_events.csv");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(w)) {

            out.println("# match_codes: " + String.join(", ", matchCodes));
            out.println("match,offset_seconds,wall_clock_unix,type,player_name,player_uuid,player_team,"
                    + "killer_name,killer_uuid,killer_team,bed_team,final,cause,was_spectating");

            for (Map<String, Object> ev : allEvents) {
                List<String> row = new ArrayList<>();
                row.add(csv(String.valueOf(ev.getOrDefault("_match", ""))));
                row.addAll(eventRow(ev));
                out.println(String.join(",", row));
            }
        }

        return outFile;
    }

    private static List<String> eventRow(Map<String, Object> ev) {
        return List.of(
                String.valueOf(ev.getOrDefault("offset", 0)),
                String.valueOf(ev.getOrDefault("timestamp", 0)),
                csv(String.valueOf(ev.getOrDefault("type", ""))),
                csv(String.valueOf(ev.getOrDefault("player_name", ""))),
                csv(String.valueOf(ev.getOrDefault("player_uuid", ""))),
                csv(String.valueOf(ev.getOrDefault("player_team", ""))),
                csv(String.valueOf(ev.getOrDefault("killer_name", ""))),
                csv(String.valueOf(ev.getOrDefault("killer_uuid", ""))),
                csv(String.valueOf(ev.getOrDefault("killer_team", ""))),
                csv(String.valueOf(ev.getOrDefault("bed_team", ""))),
                String.valueOf(ev.getOrDefault("final", false)),
                csv(String.valueOf(ev.getOrDefault("cause", ""))),
                String.valueOf(ev.getOrDefault("was_spectating", false))
        );
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
