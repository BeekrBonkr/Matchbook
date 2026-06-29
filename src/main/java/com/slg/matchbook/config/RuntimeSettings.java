package com.slg.matchbook.config;

import java.util.List;

/**
 * Parsed, validated config values that are used frequently at runtime.
 *
 * This is immutable on purpose: on /matchbook reload we simply swap the instance.
 */
public record RuntimeSettings(
        List<String> trackedKeys,
        int runningWaitTicksMax,
        long startSnapshotDelayTicks,
        long endSnapshotDelayTicks,
        long snapshotTimeoutTicks,
        ExportSettings export,
        PartySettings party
) {

    public record ExportSettings(
            /**
             * Columns for CSV export. May include meta columns (uuid, username, team)
             * and/or stat keys (e.g. bedwars:kills).
             *
             * If null/empty, exporter should fall back to smart defaults.
             */
            List<String> columns
    ) {}

    public record PartySettings(
            boolean followLeaderToArena,
            long followDelayTicks
    ) {}
}
