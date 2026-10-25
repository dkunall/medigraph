package com.medigraph.prescription.algo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Burkhard-Keller tree over Levenshtein distance. Each child edge is labelled with its distance to the parent;
 * by the triangle inequality a query at radius r only needs to descend edges labelled in [d-r, d+r], which prunes
 * most of the tree for small r.
 *
 * <p>Complexity: insert O(depth * L^2) (one Levenshtein per level); query at radius r visits only edges in [d-r, d+r], typically a small fraction of n for r <= 2, worst case O(n * L^2). Space O(n).
 */
public final class BkTree {

    public record Match(String term, int distance) {
    }

    private static final class Node {
        final String term;
        final Map<Integer, Node> children = new HashMap<>();

        Node(String term) {
            this.term = term;
        }
    }

    private Node root;
    private int size;
    private int lastVisited;

    public void add(String term) {
        if (root == null) {
            root = new Node(term);
            size++;
            return;
        }
        Node n = root;
        while (true) {
            int d = Levenshtein.distance(term, n.term);
            if (d == 0) {
                return; // duplicate
            }
            Node child = n.children.get(d);
            if (child == null) {
                n.children.put(d, new Node(term));
                size++;
                return;
            }
            n = child;
        }
    }

    /** All terms within {@code maxDistance}, nearest first, ties alphabetical. */
    public List<Match> search(String query, int maxDistance) {
        List<Match> out = new ArrayList<>();
        lastVisited = 0;
        if (root == null) {
            return out;
        }
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            lastVisited++;
            int d = Levenshtein.distance(query, n.term);
            if (d <= maxDistance) {
                out.add(new Match(n.term, d));
            }
            for (int k = Math.max(1, d - maxDistance); k <= d + maxDistance; k++) {
                Node c = n.children.get(k);
                if (c != null) {
                    stack.push(c);
                }
            }
        }
        out.sort(Comparator.comparingInt(Match::distance).thenComparing(Match::term));
        return out;
    }

    public int size() {
        return size;
    }

    /** Nodes whose distance was computed in the last search (shows the pruning). */
    public int lastVisited() {
        return lastVisited;
    }
}
