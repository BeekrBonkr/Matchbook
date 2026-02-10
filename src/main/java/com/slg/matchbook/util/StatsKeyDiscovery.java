package com.slg.matchbook.util;

import de.marcely.bedwars.api.player.PlayerStats;

import java.util.*;

/**
 * Best-effort key enumeration for MBedwars PlayerStats.
 *
 * Not all MBedwars versions expose a public method to iterate keys.
 * This utility tries several common method names and return types via reflection.
 */
public final class StatsKeyDiscovery {

    private StatsKeyDiscovery() {}

    public static Set<String> discoverKeys(PlayerStats stats) {
        if (stats == null) return Set.of();

        // Common patterns: stats.keySet()/keys()/getKeys()/getRegisteredKeys() etc.
        Object maybeKeys = invokeFirst(stats, new String[]{
                "keySet",
                "keys",
                "getKeys",
                "getKeySet",
                "getRegisteredKeys",
                "getAllKeys"
        });

        Set<String> out = new TreeSet<>();

        if (maybeKeys instanceof Collection<?> c) {
            for (Object o : c) if (o != null) out.add(o.toString());
            return out;
        }

        if (maybeKeys instanceof Object[] arr) {
            for (Object o : arr) if (o != null) out.add(o.toString());
            return out;
        }

        // Some implementations are Map-like.
        if (stats instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) if (k != null) out.add(k.toString());
            return out;
        }

        // Fall back: try to find a field that looks like a map of values.
        try {
            for (var f : stats.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(stats);
                if (v instanceof Map<?, ?> m) {
                    for (Object k : m.keySet()) if (k != null) out.add(k.toString());
                    if (!out.isEmpty()) return out;
                }
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private static Object invokeFirst(Object target, String[] names) {
        for (String name : names) {
            try {
                var m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
