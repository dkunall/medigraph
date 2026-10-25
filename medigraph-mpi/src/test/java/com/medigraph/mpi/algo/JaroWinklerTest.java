package com.medigraph.mpi.algo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JaroWinklerTest {

    @ParameterizedTest
    @CsvSource({
            "MARTHA,MARHTA,0.9611",
            "DWAYNE,DUANE,0.8400",
            "DIXON,DICKSONX,0.8133",
            "JELLYFISH,SMELLYFISH,0.8963"
    })
    void winklerPaperVectors(String a, String b, double expected) {
        assertThat(JaroWinkler.similarity(a, b)).isCloseTo(expected, within(0.0005));
    }

    @Test
    void plainJaroVector() {
        assertThat(JaroWinkler.jaro("martha", "marhta")).isCloseTo(0.9444, within(0.0005));
    }

    @Test
    void edgeCases() {
        assertThat(JaroWinkler.similarity("", "")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("abc", "")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity(null, "x")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("abc", "xyz")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("Kunal", "kunal ")).isEqualTo(1.0);
    }

    @Test
    void symmetricAndBounded() {
        String[] w = {"kumar", "kumaar", "kunal", "kamal", "sharma", "verma", "a", "ab"};
        for (String x : w) {
            for (String y : w) {
                double s = JaroWinkler.similarity(x, y);
                assertThat(s).isBetween(0.0, 1.0);
                assertThat(s).isCloseTo(JaroWinkler.similarity(y, x), within(1e-12));
            }
        }
    }
}
