package com.medigraph.prescription;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drug-drug interactions as an undirected graph in adjacency-list form: drug -> (neighbour -> edge).
 * Checking a new drug against k current medications is O(k) hash lookups, independent of graph size.
 */
public final class InteractionGraph {

    private final Map<String, Map<String, Interaction>> adjacency = new HashMap<>();

    public void add(Interaction i) {
        if (i.drugA().equals(i.drugB())) {
            throw new IllegalArgumentException("self-interaction: " + i.drugA());
        }
        adjacency.computeIfAbsent(i.drugA(), k -> new HashMap<>()).put(i.drugB(), i);
        adjacency.computeIfAbsent(i.drugB(), k -> new HashMap<>()).put(i.drugA(), i);
    }

    public Optional<Interaction> between(String a, String b) {
        return Optional.ofNullable(adjacency.getOrDefault(a, Map.of()).get(b));
    }

    /** Edges between {@code drug} and any of {@code others}, most severe first. */
    public List<Interaction> interactionsWith(String drug, Collection<String> others) {
        Map<String, Interaction> neighbours = adjacency.getOrDefault(drug, Map.of());
        List<Interaction> out = new ArrayList<>();
        for (String o : others) {
            Interaction i = neighbours.get(o);
            if (i != null) {
                out.add(i);
            }
        }
        out.sort(Comparator.comparing(Interaction::severity).reversed());
        return out;
    }

    public Collection<Interaction> neighbours(String drug) {
        return adjacency.getOrDefault(drug, Map.of()).values();
    }

    public int degree(String drug) {
        return adjacency.getOrDefault(drug, Map.of()).size();
    }

    public int edgeCount() {
        return adjacency.values().stream().mapToInt(Map::size).sum() / 2;
    }
}
