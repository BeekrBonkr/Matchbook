package com.slg.matchbook.service;

import de.marcely.bedwars.api.player.PlayerStats;

/**
 * Normalizes the "stats" object returned by MBedwars PlayerDataAPI#getStats.
 *
 * MBedwars has had some API shape changes across versions; some return PlayerStats directly,
 * others return a wrapper with methods like getOverallStats/getGameStats.
 */
public final class BedwarsStatsAdapter {

    private BedwarsStatsAdapter() {}

    public static PlayerStats pickBest(Object statsObj) {
        if (statsObj == null) return null;
        if (statsObj instanceof PlayerStats ps) return ps;

        Object overall = invokeFirst(statsObj, new String[]{"getStats", "getOverallStats", "getGlobalStats"});
        if (overall instanceof PlayerStats ps) return ps;

        Object game = invokeFirst(statsObj, new String[]{"getGameStats", "getCurrentGameStats"});
        if (game instanceof PlayerStats ps) return ps;

        return null;
    }

    private static Object invokeFirst(Object target, String[] methodNames) {
        for (String name : methodNames) {
            try {
                var m = target.getClass().getMethod(name);
                Object out = m.invoke(target);
                if (out != null) return out;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
