package com.slg.matchbook.util;

import java.security.SecureRandom;

public final class MatchIdUtil {

    private static final SecureRandom RNG = new SecureRandom();

    // Crockford Base32 alphabet (no I,L,O,U) - good for humans
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /**
     * Characters per group, either side of the dash.
     *
     * Ids are drawn at random and never checked for uniqueness at allocation time, so the only thing
     * standing between a server and a collision is the size of the space. At 4+4 that space is 32^8
     * (~1.1e12), which sounds ample but is a birthday problem: a server that accumulates 100k matches
     * has roughly a 0.5% chance of drawing the same id twice. 5+5 is 32^10 (~1.1e15) and takes the
     * same 100k matches to ~5e-6 — two more characters to type, three orders of magnitude of headroom.
     *
     * Existing shorter ids stay valid: nothing parses or validates the length, and the MySQL column is
     * VARCHAR(16). A collision that somehow still happens is caught and refused rather than silently
     * overwriting the stored match (see MySqlMatchRepository#assertNotAnIdCollision).
     */
    private static final int GROUP_LENGTH = 5;

    private MatchIdUtil() {}

    /** Returns something like "8F3KQ-2JDXW" */
    public static String newMatchId() {
        StringBuilder sb = new StringBuilder(GROUP_LENGTH * 2 + 1);
        for (int i = 0; i < GROUP_LENGTH; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        sb.append('-');
        for (int i = 0; i < GROUP_LENGTH; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
