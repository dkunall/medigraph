package com.medigraph.prescription.algo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrugTrieTest {

    @Test
    void prefixSearchIsAlphabeticalCaseInsensitiveAndLimited() {
        DrugTrie t = new DrugTrie();
        for (String w : new String[]{"metformin", "metoprolol", "methotrexate", "morphine", "amlodipine", "met"}) {
            t.insert(w);
        }
        assertThat(t.withPrefix("MET", 10)).containsExactly("met", "metformin", "methotrexate", "metoprolol");
        assertThat(t.withPrefix("met", 2)).containsExactly("met", "metformin");
        assertThat(t.withPrefix("x", 10)).isEmpty();
        assertThat(t.withPrefix("", 100)).hasSize(6);
        assertThat(t.contains("metf")).isFalse();
        assertThat(t.contains("Metformin")).isTrue();
        t.insert("metformin");
        assertThat(t.size()).isEqualTo(6);
    }
}
