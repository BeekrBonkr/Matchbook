package com.slg.matchbook.service;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.UserMatchIndex;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the timezone match dates/times are displayed in for a given viewer.
 *
 * Matches are stored as absolute unix timestamps, so this is purely a display concern.
 * Resolution order: the player's personal override (users/&lt;uuid&gt;.yml, set via
 * /mb timezone) → the display.timezone config default → the server machine's zone.
 *
 * {@link #zoneFor} and the setters can hit disk on a cache miss — call them off the
 * main thread (the GUIs already build inventories on async tasks).
 */
public final class TimezoneService {

    /** Friendly US-centric aliases so players don't need to know IANA zone ids. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("eastern", "America/New_York"),
            Map.entry("et", "America/New_York"),
            Map.entry("est", "America/New_York"),
            Map.entry("edt", "America/New_York"),
            Map.entry("central", "America/Chicago"),
            Map.entry("ct", "America/Chicago"),
            Map.entry("cst", "America/Chicago"),
            Map.entry("cdt", "America/Chicago"),
            Map.entry("mountain", "America/Denver"),
            Map.entry("mt", "America/Denver"),
            Map.entry("mst", "America/Denver"),
            Map.entry("mdt", "America/Denver"),
            Map.entry("pacific", "America/Los_Angeles"),
            Map.entry("pt", "America/Los_Angeles"),
            Map.entry("pst", "America/Los_Angeles"),
            Map.entry("pdt", "America/Los_Angeles"),
            Map.entry("alaska", "America/Anchorage"),
            Map.entry("akst", "America/Anchorage"),
            Map.entry("akdt", "America/Anchorage"),
            Map.entry("hawaii", "Pacific/Honolulu"),
            Map.entry("hst", "Pacific/Honolulu"),
            Map.entry("arizona", "America/Phoenix"),
            Map.entry("utc", "UTC"),
            Map.entry("gmt", "UTC")
    );

    private static final DateTimeFormatter CLOCK_FMT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final MatchbookPlugin plugin;
    private final UserMatchIndex userIndex;

    /** Empty Optional = "checked disk, no (valid) override". */
    private final ConcurrentMap<UUID, Optional<ZoneId>> overrides = new ConcurrentHashMap<>();

    private volatile boolean warnedBadConfigZone = false;

    public TimezoneService(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.userIndex = new UserMatchIndex(plugin);
    }

    /** The effective display zone for a player. May read users/&lt;uuid&gt;.yml on first call. */
    public ZoneId zoneFor(UUID player) {
        return loadOverride(player).orElseGet(this::defaultZone);
    }

    /** Whether the player has a personal override (vs. just inheriting the default). */
    public boolean hasOverride(UUID player) {
        return loadOverride(player).isPresent();
    }

    /** The server-wide default: display.timezone from config, or the machine's zone. */
    public ZoneId defaultZone() {
        String cfg = plugin.getMatchbookConfig().displayTimezone();
        if (cfg != null && !cfg.isBlank() && !cfg.trim().equalsIgnoreCase("server")) {
            ZoneId zone = parseZone(cfg);
            if (zone != null) return zone;
            if (!warnedBadConfigZone) {
                warnedBadConfigZone = true;
                plugin.getLogger().warning("Matchbook: display.timezone \"" + cfg
                        + "\" is not a valid timezone — falling back to the server's zone. "
                        + "Use an IANA id like America/New_York.");
            }
        }
        return ZoneId.systemDefault();
    }

    /** Sets a personal override and persists it. Call off the main thread. */
    public void setZone(UUID player, ZoneId zone) {
        overrides.put(player, Optional.of(zone));
        userIndex.setTimezone(player, zone.getId());
    }

    /** Clears a personal override so the player follows the server default again. */
    public void clearZone(UUID player) {
        overrides.put(player, Optional.empty());
        userIndex.setTimezone(player, null);
    }

    private Optional<ZoneId> loadOverride(UUID player) {
        return overrides.computeIfAbsent(player, uuid -> {
            String stored = userIndex.getTimezone(uuid);
            return Optional.ofNullable(stored == null ? null : parseZone(stored));
        });
    }

    /**
     * Parses a player-supplied zone: friendly aliases first (so "est" means US Eastern with
     * DST, not the fixed -05:00 offset), then exact IANA ids, then a case-insensitive scan
     * so "america/new_york" works too. Returns null if nothing matches.
     */
    public static ZoneId parseZone(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (t.isEmpty()) return null;

        String alias = ALIASES.get(t.toLowerCase(Locale.ROOT));
        if (alias != null) return ZoneId.of(alias);

        try {
            return ZoneId.of(t);
        } catch (Exception ignored) {
        }

        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.equalsIgnoreCase(t)) return ZoneId.of(id);
        }
        return null;
    }

    /** The current wall-clock time in a zone, e.g. "3:41 PM" — for command feedback. */
    public static String currentTimeIn(ZoneId zone) {
        return CLOCK_FMT.withZone(zone).format(Instant.now());
    }
}
