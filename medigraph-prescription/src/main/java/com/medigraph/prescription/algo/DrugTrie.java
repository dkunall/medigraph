package com.medigraph.prescription.algo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prefix tree for type-ahead drug search. Lookup is O(p) to reach the prefix node plus O(output) to collect,
 * independent of dictionary size. Children are kept sorted so results come back alphabetically.
 */
public final class DrugTrie {

    private static final class Node {
        final Map<Character, Node> children = new TreeMap<>();
        String word;
    }

    private final Node root = new Node();
    private int size;

    public void insert(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        Node n = root;
        for (int i = 0; i < w.length(); i++) {
            n = n.children.computeIfAbsent(w.charAt(i), c -> new Node());
        }
        if (n.word == null) {
            size++;
        }
        n.word = w;
    }

    public boolean contains(String word) {
        Node n = walk(word.toLowerCase(Locale.ROOT));
        return n != null && n.word != null;
    }

    public List<String> withPrefix(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        Node n = walk(prefix.toLowerCase(Locale.ROOT));
        if (n != null && limit > 0) {
            collect(n, out, limit);
        }
        return out;
    }

    public int size() {
        return size;
    }

    private Node walk(String s) {
        Node n = root;
        for (int i = 0; i < s.length() && n != null; i++) {
            n = n.children.get(s.charAt(i));
        }
        return n;
    }

    private void collect(Node n, List<String> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (n.word != null) {
            out.add(n.word);
        }
        for (Node c : n.children.values()) {
            collect(c, out, limit);
            if (out.size() >= limit) {
                return;
            }
        }
    }
}
