package com.medigraph.mpi.algo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoundexTest {

    @ParameterizedTest
    @CsvSource({
            "Robert,R163", "Rupert,R163", "Rubin,R150", "Ashcraft,A261", "Ashcroft,A261",
            "Tymczak,T522", "Pfister,P236", "Honeyman,H555", "Lee,L000", "Gutierrez,G362",
            "Sharma,S650", "Sarma,S650", "Kumar,K560", "Kumaar,K560"
    })
    void censusVectors(String name, String code) {
        assertThat(Soundex.encode(name)).isEqualTo(code);
    }

    @Test
    void ignoresCaseAndPunctuation() {
        assertThat(Soundex.encode("o'brien")).isEqualTo(Soundex.encode("OBRIEN"));
        assertThat(Soundex.encode("  ")).isEmpty();
        assertThat(Soundex.encode(null)).isEmpty();
    }
}
