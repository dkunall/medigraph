package com.medigraph.prescription;

import com.medigraph.prescription.algo.BkTree;
import com.medigraph.prescription.algo.DrugTrie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drugs, their classes, the interaction graph and the name indexes (trie for prefix search, BK-tree for typo
 * correction). Loaded from {@code sample-drug-data.txt}: SAMPLE DATA, NOT CLINICAL ADVICE.
 */
public final class DrugCatalog {

    public static final String SAMPLE_DATA_NOTICE =
            "Sample dataset for software demonstration only. Not clinical advice.";

    private final Map<String, Drug> drugs = new HashMap<>();
    private final InteractionGraph graph = new InteractionGraph();
    private final List<CrossReactivity> crossReactivity = new ArrayList<>();
    private final DrugTrie trie = new DrugTrie();
    private final BkTree bkTree = new BkTree();

    public static String normalize(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static DrugCatalog loadSample() {
        try (InputStream in = DrugCatalog.class.getResourceAsStream("/sample-drug-data.txt")) {
            if (in == null) {
                throw new IllegalStateException("sample-drug-data.txt missing from classpath");
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DrugCatalog parse(List<String> lines) {
        DrugCatalog c = new DrugCatalog();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|");
            try {
                switch (f[0]) {
                    case "DRUG" -> c.addDrug(new Drug(f[1], new LinkedHashSet<>(Arrays.asList(f[2].split(",")))));
                    case "INTERACTION" -> c.addInteraction(normalize(f[1]), normalize(f[2]), Severity.valueOf(f[3]), f[4]);
                    case "CROSS" -> c.crossReactivity.add(new CrossReactivity(f[1], f[2], Severity.valueOf(f[3]), f[4]));
                    default -> throw new IllegalArgumentException("unknown record type " + f[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bad drug data at line " + n + ": " + raw, e);
            }
        }
        return c;
    }

    public void addDrug(Drug d) {
        drugs.put(d.name(), d);
        trie.insert(d.name());
        bkTree.add(d.name());
    }

    private void addInteraction(String a, String b, Severity s, String description) {
        if (!drugs.containsKey(a) || !drugs.containsKey(b)) {
            throw new IllegalArgumentException("interaction references unknown drug: " + a + " / " + b);
        }
        graph.add(new Interaction(a, b, s, description));
    }

    public Optional<Drug> drug(String name) {
        return Optional.ofNullable(drugs.get(normalize(name)));
    }

    public Collection<Drug> drugs() {
        return drugs.values();
    }

    public InteractionGraph graph() {
        return graph;
    }

    public List<CrossReactivity> crossReactivity() {
        return crossReactivity;
    }

    public DrugTrie trie() {
        return trie;
    }

    public BkTree bkTree() {
        return bkTree;
    }

    public Set<String> allClasses() {
        Set<String> s = new LinkedHashSet<>();
        drugs.values().forEach(d -> s.addAll(d.classes()));
        return s;
    }
}
