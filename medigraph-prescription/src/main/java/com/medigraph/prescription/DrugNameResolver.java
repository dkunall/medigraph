package com.medigraph.prescription;

import com.medigraph.prescription.algo.BkTree;

import java.util.List;

/** Maps free-typed drug names to catalog entries: exact, then typo-corrected via the BK-tree. */
public final class DrugNameResolver {

    public enum Kind { EXACT, CORRECTED, AMBIGUOUS, UNKNOWN }

    public record Resolution(Kind kind, String input, String drug, List<String> suggestions) {
        public boolean resolved() {
            return kind == Kind.EXACT || kind == Kind.CORRECTED;
        }
    }

    private final DrugCatalog catalog;

    public DrugNameResolver(DrugCatalog catalog) {
        this.catalog = catalog;
    }

    /** Short names tolerate fewer edits, otherwise "lithium" vs "lisinopril"-style false corrections creep in. */
    static int toleranceFor(String s) {
        return s.length() <= 4 ? 0 : s.length() <= 8 ? 1 : 2;
    }

    public Resolution resolve(String input) {
        String q = DrugCatalog.normalize(input);
        if (catalog.drug(q).isPresent()) {
            return new Resolution(Kind.EXACT, input, q, List.of(q));
        }
        int tol = toleranceFor(q);
        List<BkTree.Match> matches = catalog.bkTree().search(q, Math.max(tol, 2));
        List<String> suggestions = matches.stream().limit(5).map(BkTree.Match::term).toList();
        List<BkTree.Match> within = matches.stream().filter(m -> m.distance() <= tol).toList();
        if (within.isEmpty()) {
            return new Resolution(Kind.UNKNOWN, input, null, suggestions);
        }
        int best = within.get(0).distance();
        List<BkTree.Match> top = within.stream().filter(m -> m.distance() == best).toList();
        if (top.size() > 1) {
            // never guess between two drugs; the prescriber must pick
            return new Resolution(Kind.AMBIGUOUS, input, null, top.stream().map(BkTree.Match::term).toList());
        }
        return new Resolution(Kind.CORRECTED, input, top.get(0).term(), suggestions);
    }
}
