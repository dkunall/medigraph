package com.medigraph.mpi.algo;

/**
 * Jaro-Winkler string similarity in [0, 1], implemented by hand.
 *
 * <p>Jaro: characters match if equal and within {@code floor(max(|a|,|b|)/2) - 1} positions; with {@code m} matches
 * and {@code t} half-transpositions, {@code jaro = (m/|a| + m/|b| + (m - t/2)/m) / 3}. Winkler boosts pairs whose
 * Jaro score exceeds {@link #BOOST_THRESHOLD} by {@code l * p * (1 - jaro)} where {@code l} is the common prefix
 * length (max 4) and {@code p} = 0.1. Comparison is case-insensitive and ignores surrounding whitespace.
 *
 * <p>Complexity: O(|a| * w) time where w = match window (worst case O(|a| * |b|)), O(|a| + |b|) space for the match flags.
 */
public final class JaroWinkler {

    public static final double PREFIX_SCALE = 0.1;
    public static final int MAX_PREFIX = 4;
    public static final double BOOST_THRESHOLD = 0.7;

    private JaroWinkler() {
    }

    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        String a = s1.trim().toLowerCase();
        String b = s2.trim().toLowerCase();
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double jaro = jaro(a, b);
        if (jaro < BOOST_THRESHOLD) {
            return jaro;
        }
        int prefix = 0;
        int limit = Math.min(MAX_PREFIX, Math.min(a.length(), b.length()));
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro);
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int halfTranspositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                halfTranspositions++;
            }
            k++;
        }
        double m = matches;
        return (m / a.length() + m / b.length() + (m - halfTranspositions / 2.0) / m) / 3.0;
    }
}
