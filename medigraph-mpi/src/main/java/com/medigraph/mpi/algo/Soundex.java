package com.medigraph.mpi.algo;

/**
 * American Soundex (the variant used by the US census), implemented by hand.
 *
 * <p>Rules: keep the first letter; map the rest to digit classes; collapse adjacent identical codes; letters
 * separated by H or W count as adjacent (so they collapse), but a vowel between them resets the run; drop vowels;
 * pad/truncate to letter + 3 digits. Non-letters are ignored. Used as a <i>blocking</i> key, not as a matcher.
 *
 * <p>Complexity: O(n) time in the name length, O(1) extra space (fixed 4-char code).
 */
public final class Soundex {

    //                                       ABCDEFGHIJKLMNOPQRSTUVWXYZ
    private static final String CODES = "01230120022455012623010202";

    private Soundex() {
    }

    public static String encode(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder letters = new StringBuilder();
        for (char ch : name.toUpperCase().toCharArray()) {
            if (ch >= 'A' && ch <= 'Z') {
                letters.append(ch);
            }
        }
        if (letters.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(4);
        char first = letters.charAt(0);
        out.append(first);
        char lastCode = code(first);
        for (int i = 1; i < letters.length() && out.length() < 4; i++) {
            char ch = letters.charAt(i);
            char c = code(ch);
            if (ch == 'H' || ch == 'W') {
                continue; // transparent: does not reset lastCode
            }
            if (c == '0') {
                lastCode = '0'; // vowel (and Y): separates equal codes
                continue;
            }
            if (c != lastCode) {
                out.append(c);
            }
            lastCode = c;
        }
        while (out.length() < 4) {
            out.append('0');
        }
        return out.toString();
    }

    private static char code(char upper) {
        return CODES.charAt(upper - 'A');
    }
}
