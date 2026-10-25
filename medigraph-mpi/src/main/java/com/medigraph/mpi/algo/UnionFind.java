package com.medigraph.mpi.algo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disjoint-set forest over arbitrary keys with union by rank and path compression (path halving), giving
 * amortized O(alpha(n)) find/union. Used to keep duplicate-patient clusters transitively closed: if A~B and B~C were
 * linked separately, A, B and C resolve to one enterprise identity.
 *
 * <p>Not thread-safe; the owning index guards it.
 */
public final class UnionFind<K> {

    private final Map<K, K> parent = new HashMap<>();
    private final Map<K, Integer> rank = new HashMap<>();
    private int components;

    /** Adds {@code k} as a singleton set; no-op if present. */
    public void add(K k) {
        if (parent.putIfAbsent(k, k) == null) {
            rank.put(k, 0);
            components++;
        }
    }

    public boolean contains(K k) {
        return parent.containsKey(k);
    }

    public K find(K k) {
        K p = parent.get(k);
        if (p == null) {
            throw new IllegalArgumentException("unknown element: " + k);
        }
        K x = k;
        while (!parent.get(x).equals(x)) {
            K grand = parent.get(parent.get(x));
            parent.put(x, grand); // path halving
            x = grand;
        }
        return x;
    }

    /**
     * Merges the sets of {@code a} and {@code b}.
     *
     * @return the new root, or the existing common root if already connected
     */
    public K union(K a, K b) {
        K ra = find(a);
        K rb = find(b);
        if (ra.equals(rb)) {
            return ra;
        }
        int rankA = rank.get(ra);
        int rankB = rank.get(rb);
        components--;
        if (rankA < rankB) {
            parent.put(ra, rb);
            return rb;
        }
        if (rankA > rankB) {
            parent.put(rb, ra);
            return ra;
        }
        parent.put(rb, ra);
        rank.put(ra, rankA + 1);
        return ra;
    }

    public boolean connected(K a, K b) {
        return find(a).equals(find(b));
    }

    public int componentCount() {
        return components;
    }

    public int size() {
        return parent.size();
    }

    /** All members of {@code k}'s set (O(n) scan; intended for API reads, not hot paths). */
    public List<K> members(K k) {
        K root = find(k);
        List<K> out = new ArrayList<>();
        for (K x : new ArrayList<>(parent.keySet())) {
            if (find(x).equals(root)) {
                out.add(x);
            }
        }
        return out;
    }

    public Map<K, List<K>> components() {
        Map<K, List<K>> out = new HashMap<>();
        Collection<K> keys = new ArrayList<>(parent.keySet());
        for (K x : keys) {
            out.computeIfAbsent(find(x), r -> new ArrayList<>()).add(x);
        }
        return out;
    }
}
