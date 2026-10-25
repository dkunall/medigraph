package com.medigraph.service.prescription.application;

import com.medigraph.prescription.DrugCatalog;
import com.medigraph.prescription.DrugNameResolver;

import java.util.List;

/** Prescriber-facing name lookup: trie prefix autocomplete, BK-tree typo correction. */
public final class DrugLookup {

    public static final int MAX_SUGGESTIONS = 20;

    private final DrugCatalog catalog;
    private final DrugNameResolver resolver;

    public DrugLookup(DrugCatalog catalog) {
        this.catalog = catalog;
        this.resolver = new DrugNameResolver(catalog);
    }

    /** O(p + k) for a prefix of length p and k results (see {@code DrugTrie}). */
    public List<String> suggest(String prefix, int limit) {
        return catalog.trie().withPrefix(DrugCatalog.normalize(prefix), Math.min(Math.max(limit, 1), MAX_SUGGESTIONS));
    }

    public DrugNameResolver.Resolution resolve(String name) {
        return resolver.resolve(name);
    }
}
