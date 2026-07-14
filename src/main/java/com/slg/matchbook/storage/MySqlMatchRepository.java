package com.slg.matchbook.storage;

import com.slg.matchbook.MatchSession;
import com.slg.matchbook.io.MatchYamlCodec;
import com.slg.matchbook.model.MatchDocument;
import com.slg.matchbook.MatchStorage;
import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.UserMatchIndex;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * MySQL-backed storage.
 *
 * Design goals:
 *  - Store the authoritative match document as YAML text (same shape as YAML mode)
 *  - Maintain a player->match index for fast history lookups
 *
 * This keeps exporters/GUI working with minimal branching.
 */
public final class MySqlMatchRepository implements MatchRepository {

    private final MatchbookPlugin plugin;
    private final YamlConfiguration cfg;
    private HikariDataSource ds;
    private String prefix;

    public MySqlMatchRepository(MatchbookPlugin plugin, YamlConfiguration cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    @Override
    public void init() throws Exception {
        init(true);
    }

    /**
     * @param ensureSchema When false, connects without running CREATE TABLE/ALTER TABLE DDL. Used by
     *                     migration dry-runs, which must not mutate the database while only previewing.
     */
    public void init(boolean ensureSchema) throws Exception {
        this.prefix = s("mysql.table_prefix", "storage.mysql.table_prefix", "matchbook_");

        String host = s("mysql.host", "storage.mysql.host", "127.0.0.1");
        int port = i("mysql.port", "storage.mysql.port", 3306);
        String database = s("mysql.database", "storage.mysql.database", "minecraft");
        String username = s("mysql.username", "storage.mysql.username", "root");
        String password = s("mysql.password", "storage.mysql.password", "");

        String params = s("mysql.params", "storage.mysql.params",
                "useUnicode=true&characterEncoding=utf8&useSSL=true&requireSSL=true&verifyServerCertificate=true&allowPublicKeyRetrieval=true&serverTimezone=UTC");

        // Safety: trim and strip leading '?'
        params = params == null ? "" : params.trim();
        if (params.startsWith("?")) params = params.substring(1);

        // Build the JDBC URL correctly
        String jdbc = "jdbc:mysql://" + host + ":" + port + "/" + database;
        if (!params.isEmpty()) jdbc += "?" + params;

        // Log URL without leaking credentials
        plugin.getLogger().info("[Matchbook] MySQL JDBC: " + jdbc);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbc);
        hc.setUsername(username);
        hc.setPassword(password);

        hc.setMaximumPoolSize(i("mysql.pool.maximum_pool_size", "storage.mysql.pool.maximum_pool_size", 10));
        hc.setMinimumIdle(i("mysql.pool.minimum_idle", "storage.mysql.pool.minimum_idle", 2));
        hc.setConnectionTimeout(l("mysql.pool.connection_timeout_ms", "storage.mysql.pool.connection_timeout_ms", 10000));
        hc.setIdleTimeout(l("mysql.pool.idle_timeout_ms", "storage.mysql.pool.idle_timeout_ms", 300000));
        hc.setMaxLifetime(l("mysql.pool.max_lifetime_ms", "storage.mysql.pool.max_lifetime_ms", 1800000));

        // fail fast if DB can't connect
        hc.setInitializationFailTimeout(10000);

        this.ds = new HikariDataSource(hc);

        if (ensureSchema) ensureSchema();
        plugin.getLogger().info("Matchbook: MySQL storage enabled (" + host + ":" + port + "/" + database + ")");
    }

    @Override
    public void shutdown() {
        if (ds != null) {
            try { ds.close(); } catch (Throwable ignored) {}
            ds = null;
        }
    }

    @Override
    public HealthCheckResult healthCheck() {
        if (ds == null) {
            return HealthCheckResult.fail("MySQL storage: datasource not initialized");
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {

            boolean ok = rs.next();
            return ok
                    ? HealthCheckResult.ok("MySQL storage: OK")
                    : HealthCheckResult.fail("MySQL storage: SELECT 1 returned no rows");
        } catch (Exception e) {
            return HealthCheckResult.fail("MySQL storage: " + e.getMessage());
        }
    }

    private void ensureSchema() throws SQLException {
        String matches = prefix + "matches";
        String pidx = prefix + "player_matches";
        final int desiredMatchIdLen = 16; // short match codes; keep a little headroom

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + matches + "` ("
                            + "`match_id` VARCHAR(" + desiredMatchIdLen + ") NOT NULL,"
                            + "`start_unix` BIGINT NOT NULL,"
                            + "`end_unix` BIGINT NOT NULL,"
                            + "`arena` VARCHAR(128) NOT NULL,"
                            + "`result` VARCHAR(32) NOT NULL,"
                            + "`yaml` LONGTEXT NOT NULL,"
                            + "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + "PRIMARY KEY (`match_id`),"
                            + "INDEX (`start_unix`),"
                            + "INDEX (`arena`)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
            );

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + pidx + "` ("
                            + "`player_uuid` CHAR(36) NOT NULL,"
                            + "`match_id` VARCHAR(" + desiredMatchIdLen + ") NOT NULL,"
                            + "`username` VARCHAR(16) NULL,"
                            + "`team` VARCHAR(16) NULL,"
                            + "PRIMARY KEY (`player_uuid`, `match_id`),"
                            + "INDEX (`match_id`),"
                            + "CONSTRAINT `fk_" + pidx + "_match` FOREIGN KEY (`match_id`)"
                            + " REFERENCES `" + matches + "`(`match_id`) ON DELETE CASCADE"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
            );

            // If the tables already existed from an older version, ensure the match_id column is wide enough.
            // This prevents "Data too long for column 'match_id'" when upgrading.
            ensureColumnLengthAtLeast(c, matches, "match_id", desiredMatchIdLen);
            ensureColumnLengthAtLeast(c, pidx, "match_id", desiredMatchIdLen);
        }
    }

    private void ensureColumnLengthAtLeast(Connection c, String table, String column, int minLen) {
        try {
            // INFORMATION_SCHEMA is available on MySQL/MariaDB.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT CHARACTER_MAXIMUM_LENGTH " +
                            "FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    long len = rs.getLong(1);
                    if (len >= minLen) return;
                }
            }

            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE `" + table + "` MODIFY `" + column + "` VARCHAR(" + minLen + ") NOT NULL");
            }

            plugin.getLogger().info("Matchbook: widened " + table + "." + column + " to VARCHAR(" + minLen + ")");
        } catch (Throwable t) {
            // Non-fatal; creation may still work. We'll log at debug level to avoid scaring admins.
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("Matchbook: could not verify/alter column length for " + table + "." + column + ": " + t.getMessage());
            }
        }
    }

    @Override
    public void saveMatch(MatchDocument doc) throws IOException {
        if (ds == null) throw new IOException("MySQL pool not initialized");

        // Build YAML document exactly like YAML mode
        String yamlText = MatchYamlCodec.toYamlString(doc);

        String matches = prefix + "matches";
        String pidx = prefix + "player_matches";

        long endUnix = doc.endUnix();

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + matches + "` (match_id, start_unix, end_unix, arena, result, yaml) "
                            + "VALUES (?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE end_unix=VALUES(end_unix), result=VALUES(result), yaml=VALUES(yaml)")) {
                ps.setString(1, doc.matchId());
                ps.setLong(2, doc.startUnix());
                ps.setLong(3, endUnix);
                ps.setString(4, doc.arenaName());
                ps.setString(5, doc.result() != null ? doc.result() : "UNKNOWN");
                ps.setString(6, yamlText);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + pidx + "` (player_uuid, match_id, username, team) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE username=VALUES(username), team=VALUES(team)")) {
                for (UUID u : doc.participants()) {
                    MatchDocument.PlayerEntry e = doc.players().get(u);
                    ps.setString(1, u.toString());
                    ps.setString(2, doc.matchId());
                    ps.setString(3, e != null ? e.username() : null);
                    var t = e != null ? e.team() : null;
                    ps.setString(4, t != null ? t.name() : null);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            c.commit();
        } catch (SQLException e) {
            throw new IOException("MySQL save failed: " + e.getMessage(), e);
        }
    }

    @Override
    public File findMatchFileById(String matchId) {
        // In MySQL mode there is no physical file.
        return null;
    }

    @Override
    public YamlConfiguration loadMatchYaml(String matchId) {
        if (ds == null) return null;

        String matches = prefix + "matches";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT yaml FROM `" + matches + "` WHERE match_id=?")) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String yaml = rs.getString(1);
                YamlConfiguration yml = new YamlConfiguration();
                yml.loadFromString(yaml);
                return yml;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Matchbook: loadMatchYaml(" + matchId + ") failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> listMatchIdsForPlayer(UUID playerUuid) {
        if (ds == null) return List.of();

        String matches = prefix + "matches";
        String pidx = prefix + "player_matches";

        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pm.match_id FROM `" + pidx + "` pm "
                             + "JOIN `" + matches + "` m ON m.match_id = pm.match_id "
                             + "WHERE pm.player_uuid=? ORDER BY m.start_unix DESC")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Matchbook: listMatchIdsForPlayer failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public List<String> listAllMatchIds() {
        if (ds == null) return List.of();

        String matches = prefix + "matches";
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT match_id FROM `" + matches + "` ORDER BY start_unix DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            plugin.getLogger().severe("Matchbook: listAllMatchIds failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Used by migration: directly upsert a match record from an existing YAML file.
     */
    public void importMatchFromYaml(YamlConfiguration yml, String yamlText) throws SQLException {
        String matchId = yml.getString("match.match_id", "");
        long startUnix = yml.getLong("match.start_unix", 0L);
        long endUnix = yml.getLong("match.end_unix", 0L);
        String arena = yml.getString("match.arena", "");
        String result = yml.getString("match.result", "");

        if (matchId == null || matchId.isBlank()) return;
        if (yamlText == null) yamlText = yml.saveToString();

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsertMatchRow(c, matchId, startUnix, endUnix, arena, result, yamlText);
                upsertPlayerIndexRows(c, matchId, yml);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // --- helpers used by migration ---

    private void upsertMatchRow(Connection c,
                               String matchId,
                               long startUnix,
                               long endUnix,
                               String arena,
                               String result,
                               String yamlText) throws SQLException {

        String matches = prefix + "matches";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `" + matches + "` (match_id, start_unix, end_unix, arena, result, yaml) "
                        + "VALUES (?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE end_unix=VALUES(end_unix), result=VALUES(result), yaml=VALUES(yaml)")) {
            ps.setString(1, matchId);
            ps.setLong(2, startUnix);
            ps.setLong(3, endUnix);
            ps.setString(4, arena != null ? arena : "");
            ps.setString(5, result != null ? result : "UNKNOWN");
            ps.setString(6, yamlText != null ? yamlText : "");
            ps.executeUpdate();
        }
    }

    private void upsertPlayerIndexRows(Connection c, String matchId, YamlConfiguration yml) throws SQLException {
        String pidx = prefix + "player_matches";

        List<String> participants = yml.getStringList("match.participants");
        if (participants == null) participants = List.of();

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `" + pidx + "` (player_uuid, match_id, username, team) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE username=VALUES(username), team=VALUES(team)")) {

            for (String uuidStr : participants) {
                String base = "players." + uuidStr;
                String username = yml.getString(base + ".username", null);
                String team = yml.getString(base + ".team", null);

                ps.setString(1, uuidStr);
                ps.setString(2, matchId);
                ps.setString(3, username);
                ps.setString(4, team);
                ps.addBatch();
            }

            ps.executeBatch();
        }
    }

    private String buildYamlText(MatchSession session, String result) {
        // Keep MySQL mode byte-for-byte consistent with YAML mode.
        return com.slg.matchbook.io.MatchYamlCodec.toYamlString(session, result);
    }

    private String s(String a, String b, String def) {
        String v = cfg.getString(a);
        if (v == null) v = cfg.getString(b);
        return v != null ? v : def;
    }
    private int i(String a, String b, int def) {
        if (cfg.contains(a)) return cfg.getInt(a);
        if (cfg.contains(b)) return cfg.getInt(b);
        return def;
    }
    private long l(String a, String b, long def) {
        if (cfg.contains(a)) return cfg.getLong(a);
        if (cfg.contains(b)) return cfg.getLong(b);
        return def;
    }

}
