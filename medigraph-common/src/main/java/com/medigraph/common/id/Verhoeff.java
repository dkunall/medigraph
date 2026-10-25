package com.medigraph.common.id;

/**
 * Verhoeff check-digit scheme, implemented by hand from the dihedral group D5.
 *
 * <p>Unlike Luhn (mod 10 weighting), Verhoeff detects all single-digit substitution errors and all
 * adjacent transpositions, because multiplication in D5 is non-commutative and the position-dependent permutation
 * {@code P} breaks the symmetry that makes {@code ab -> ba} undetectable in commutative schemes.
 *
 * <ul>
 *   <li>{@link #D} is the Cayley table of D5 (elements 0-4 rotations, 5-9 reflections).</li>
 *   <li>{@link #P} is the position permutation: row {@code i} = {@code P1^i}, where P1 = (0 1 5 8 9 4 2 7)(3 6); it has order 8.</li>
 *   <li>{@link #INV} gives the group inverse of each element.</li>
 * </ul>
 *
 * <p>Complexity: check digit / validation O(n) time over n digits, O(1) space (three constant lookup tables).
 */
public final class Verhoeff {

    static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    private Verhoeff() {
    }

    /** Computes the check digit to append to {@code digits} (a non-empty string of ASCII digits). */
    public static int checkDigit(String digits) {
        requireDigits(digits);
        int c = 0;
        int n = digits.length();
        // Walk right-to-left. Position 0 is reserved for the (future) check digit, so payload digits start at 1.
        for (int i = 0; i < n; i++) {
            int digit = digits.charAt(n - 1 - i) - '0';
            c = D[c][P[(i + 1) % 8][digit]];
        }
        return INV[c];
    }

    /** True if the last digit of {@code digitsWithCheck} is a correct Verhoeff check digit for the rest. */
    public static boolean isValid(String digitsWithCheck) {
        if (digitsWithCheck == null || digitsWithCheck.length() < 2 || !allDigits(digitsWithCheck)) {
            return false;
        }
        int c = 0;
        int n = digitsWithCheck.length();
        for (int i = 0; i < n; i++) {
            int digit = digitsWithCheck.charAt(n - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }

    private static void requireDigits(String s) {
        if (s == null || s.isEmpty() || !allDigits(s)) {
            throw new IllegalArgumentException("expected a non-empty string of digits");
        }
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
