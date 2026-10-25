package com.medigraph.prescription.algo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BkTreeTest {

    @Test
    void matchesBruteForceAndPrunes() {
        Random r = new Random(7);
        List<String> words = new ArrayList<>();
        BkTree t = new BkTree();
        for (int i = 0; i < 5000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 5 + r.nextInt(6); k > 0; k--) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            words.add(sb.toString());
            t.add(sb.toString());
        }
        for (int q = 0; q < 50; q++) {
            String query = words.get(r.nextInt(words.size())) + "x";
            List<BkTree.Match> expected = words.stream().distinct()
                    .map(w -> new BkTree.Match(w, Levenshtein.distance(query, w)))
                    .filter(m -> m.distance() <= 1)
                    .sorted(Comparator.comparingInt(BkTree.Match::distance).thenComparing(BkTree.Match::term))
                    .toList();
            assertThat(t.search(query, 1)).isEqualTo(expected);
            assertThat(t.lastVisited()).isLessThan(t.size()); // pruned
        }
    }

    @Test
    void duplicatesIgnoredAndEmptyTreeSafe() {
        BkTree t = new BkTree();
        assertThat(t.search("x", 3)).isEmpty();
        t.add("abc");
        t.add("abc");
        assertThat(t.size()).isEqualTo(1);
    }
}
