package com.slg.matchbook.util;

import java.security.SecureRandom;

public final class MatchIdUtil {

    private static final SecureRandom RNG = new SecureRandom();

    // Crockford Base32 alphabet (no I,L,O,U) - good for humans
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private MatchIdUtil() {}

    /** Returns something like "8F3K-Q2JD" */
    public static String newMatchId() {
        char[] buf = new char[9]; // XXXX-XXXX
        for (int i = 0; i < 4; i++) buf[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        buf[4] = '-';
        for (int i = 5; i < 9; i++) buf[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        return new String(buf);
    }
}
