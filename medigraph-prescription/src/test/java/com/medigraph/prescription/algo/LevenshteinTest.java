package com.medigraph.prescription.algo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LevenshteinTest {

    @ParameterizedTest
    @CsvSource({"kitten,sitting,3", "flaw,lawn,2", "'','',0", "abc,'',3", "metfromin,metformin,2", "same,same,0",
            "warfarin,warfrin,1"})
    void knownDistances(String a, String b, int d) {
        assertThat(Levenshtein.distance(a, b)).isEqualTo(d);
        assertThat(Levenshtein.distance(b, a)).isEqualTo(d);
    }

    @Test
    void isAMetric() {
        Random r = new Random(1);
        for (int i = 0; i < 300; i++) {
            String a = rnd(r), b = rnd(r), c = rnd(r);
            assertThat(Levenshtein.distance(a, c)).isLessThanOrEqualTo(Levenshtein.distance(a, b) + Levenshtein.distance(b, c));
            assertThat(Levenshtein.distance(a, b) == 0).isEqualTo(a.equals(b));
        }
    }

    private static String rnd(Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = r.nextInt(8); i > 0; i--) {
            sb.append((char) ('a' + r.nextInt(3)));
        }
        return sb.toString();
    }
}
