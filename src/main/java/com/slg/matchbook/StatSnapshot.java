package com.slg.matchbook;

import java.util.*;

public final class StatSnapshot {

    /**
     * Default tracked keys if config doesn't specify any.
     *
     * Note: this is only a fallback. Runtime code should prefer MatchbookConfig#trackedKeys().
     */
    public static final List<String> DEFAULT_TRACKED_KEYS = List.of(
            "bedwars:wins",
            "bedwars:kills",
            "bedwars:deaths",
            "bedwars:final_kills",
            "bedwars:final_deaths",
            "bedwars:beds_destroyed",
            "bedwars:loses"
    );

    private final Map<String, Long> values;

    public StatSnapshot(Map<String, Long> values) {
        // preserve insertion order
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Long> values() {
        return values;
    }

    public static StatSnapshot empty() {
        return empty(DEFAULT_TRACKED_KEYS);
    }

    public static StatSnapshot empty(List<String> keys) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (keys != null) {
            for (String k : keys) {
                if (k == null) continue;
                String t = k.trim();
                if (t.isEmpty()) continue;
                m.put(t, 0L);
            }
        }
        // If keys were empty/invalid, fall back.
        if (m.isEmpty()) {
            for (String k : DEFAULT_TRACKED_KEYS) m.put(k, 0L);
        }
        return new StatSnapshot(m);
    }

    public boolean anyNonZero() {
        for (Long v : values.values()) {
            if (v != null && v != 0L) return true;
        }
        return false;
    }

    public static Map<String, Long> diff(StatSnapshot start, StatSnapshot end) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (start == null || end == null) return out;

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(start.values.keySet());
        keys.addAll(end.values.keySet());

        for (String key : keys) {
            long a = start.values.getOrDefault(key, 0L);
            long b = end.values.getOrDefault(key, 0L);
            out.put(key, b - a);
        }

        return out;
    }
}
