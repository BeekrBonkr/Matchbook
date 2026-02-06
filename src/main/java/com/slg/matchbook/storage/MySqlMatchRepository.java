package com.slg.matchbook.storage;

import com.slg.matchbook.MatchSession;
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
        this.prefix = cfg.getString("mysql.table_prefix", "matchbook_");

        String host = cfg.getString("mysql.host", "127.0.0.1");
        int port = cfg.getInt("mysql.port", 3306);
        String database = cfg.getString("mysql.database", "minecraft");
        String username = cfg.getString("mysql.username", "root");
        String password = cfg.getString("mysql.password", "");
        boolean useSsl = cfg.getBoolean("mysql.use_ssl", false);
        boolean allowPkr = cfg.getBoolean("mysql.allow_public_key_retrieval", true);
        String tz = cfg.getString("mysql.server_timezone", "UTC");

        String jdbc = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=" + allowPkr
                + "&serverTimezone=" + tz
                + "&characterEncoding=utf8"
                + "&useUnicode=true";

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbc);
        hc.setUsername(username);
        hc.setPassword(password);

        hc.setMaximumPoolSize(cfg.getInt("mysql.pool.max_pool_size", 10));
        hc.setMinimumIdle(cfg.getInt("mysql.pool.min_idle", 2));
        hc.setConnectionTimeout(cfg.getLong("mysql.pool.connection_timeout_ms", 10000));
        hc.setIdleTimeout(cfg.getLong("mysql.pool.idle_timeout_ms", 600000));
        hc.setMaxLifetime(cfg.getLong("mysql.pool.max_lifetime_ms", 1800000));

        // Important for stability: fail fast if DB can't connect
        hc.setInitializationFailTimeout(10000);

        this.ds = new HikariDataSource(hc);

        ensureSchema();
        plugin.getLogger().info("Matchbook: MySQL storage enabled (" + host + ":" + port + "/" + database + ")");
    }

    @Override
    public void shutdown() {
        if (ds != null) {
            try { ds.close(); } catch (Throwable ignored) {}
            ds = null;
        }
    }

    private void ensureSchema() throws SQLException {
        String matches = prefix + "matches";
        String pidx = prefix + "player_matches";

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + matches + "` ("
                            + "`match_id` VARCHAR(32) NOT NULL,"
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
                            + "`match_id` VARCHAR(32) NOT NULL,"
                            + "`username` VARCHAR(16) NULL,"
                            + "`team` VARCHAR(16) NULL,"
                            + "PRIMARY KEY (`player_uuid`, `match_id`),"
                            + "INDEX (`match_id`),"
                            + "CONSTRAINT `fk_" + pidx + "_match` FOREIGN KEY (`match_id`)"
                            + " REFERENCES `" + matches + "`(`match_id`) ON DELETE CASCADE"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
            );
        }
    }

    @Override
    public void saveMatch(MatchSession session, String result) throws IOException {
        if (ds == null) throw new IOException("MySQL pool not initialized");

        // Build YAML document exactly like YAML mode
        String yamlText = buildYamlText(session, result);

        String matches = prefix + "matches";
        String pidx = prefix + "player_matches";

        long endUnix = session.endUnix != null ? session.endUnix : (System.currentTimeMillis() / 1000L);

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + matches + "` (match_id, start_unix, end_unix, arena, result, yaml) "
                            + "VALUES (?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE end_unix=VALUES(end_unix), result=VALUES(result), yaml=VALUES(yaml)")) {
                ps.setString(1, session.matchId);
                ps.setLong(2, session.startUnix);
                ps.setLong(3, endUnix);
                ps.setString(4, session.arenaName);
                ps.setString(5, result != null ? result : "UNKNOWN");
                ps.setString(6, yamlText);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + pidx + "` (player_uuid, match_id, username, team) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE username=VALUES(username), team=VALUES(team)")) {
                for (UUID u : session.getParticipants()) {
                    ps.setString(1, u.toString());
                    ps.setString(2, session.matchId);
                    ps.setString(3, session.getUsername(u));
                    var t = session.getTeam(u);
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
}
