package com.slg.matchbook.gui;

import java.util.UUID;

/**
 * Where a match-details GUI was opened from, so its Back button returns the viewer
 * to the exact list (and page) they came from rather than always to their own
 * history. Carried through the event log GUI too, so backing out of the whole
 * chain still lands on the original list. Null means the details GUI was opened
 * directly (e.g. /matchbook view) and Back falls back to the viewer's history.
 */
public sealed interface ReturnTarget {

    /** A player's match history list ({@code /matchbook matches}). */
    record History(UUID targetUuid, int page) implements ReturnTarget {}

    /** The all-matches browse list ({@code /matchbook all}). */
    record AllMatches(int page) implements ReturnTarget {}
}
