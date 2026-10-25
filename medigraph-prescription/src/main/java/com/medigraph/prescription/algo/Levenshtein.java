package com.medigraph.prescription.algo;

/** Edit distance (insert / delete / substitute, unit costs), O(n*m) time, O(min(n,m)) memory. */
public final class Levenshtein {

    private Levenshtein() {
    }

    public static int distance(CharSequence a, CharSequence b) {
        if (a.length() < b.length()) {
            CharSequence t = a;
            a = b;
            b = t;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
