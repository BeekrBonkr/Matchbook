package com.slg.matchbook;

import java.util.*;

public final class StatSnapshot {

    public static final List<String> TRACKED_KEYS = List.of(
            "bedwars:wins",
            "bedwars:kills",
            "bedwars:final_kills",
            "bedwars:loses",
            "bedwars:final_deaths",
            "bedwars:beds_destroyed"
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
        Map<String, Long> m = new LinkedHashMap<>();
        for (String k : TRACKED_KEYS) m.put(k, 0L);
        return new StatSnapshot(m);
    }

    public static Map<String, Long> diff(StatSnapshot start, StatSnapshot end) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : TRACKED_KEYS) {
            long a = start.values.getOrDefault(key, 0L);
            long b = end.values.getOrDefault(key, 0L);
            out.put(key, b - a);
        }
        return out;
    }
}
