package com.medigraph.common.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerhoeffTest {

    @Test
    void sanityVectorFromSpecValidates() {
        assertThat(Verhoeff.isValid("91274503186205")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "91275403186205", // '45' swapped to '54'
            "91254503186205"  // '74' swapped to '47' ... (positions 3-4)
    })
    void sanityVectorsWithTranspositionFail(String id) {
        assertThat(Verhoeff.isValid(id)).isFalse();
    }

    @Test
    void knownTextbookVector() {
        // Classic example from the literature: 236 -> check digit 3
        assertThat(Verhoeff.checkDigit("236")).isEqualTo(3);
        assertThat(Verhoeff.isValid("2363")).isTrue();
        assertThat(Verhoeff.checkDigit("12345")).isEqualTo(1);
    }

    @Test
    void checkDigitRoundTripsForPayloadOfSpec() {
        assertThat(Verhoeff.checkDigit("9127450318620")).isEqualTo(5);
    }

    @Test
    void detectsEverySingleDigitSubstitution() {
        SplittableRandom rnd = new SplittableRandom(42);
        int checked = 0;
        for (int sample = 0; sample < 300; sample++) {
            String id = HealthId.generate(rnd).digits();
            for (int pos = 0; pos < id.length(); pos++) {
                for (char d = '0'; d <= '9'; d++) {
                    if (d == id.charAt(pos)) {
                        continue;
                    }
                    String corrupted = id.substring(0, pos) + d + id.substring(pos + 1);
                    assertThat(Verhoeff.isValid(corrupted)).as("%s -> %s", id, corrupted).isFalse();
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(300 * 14 * 9);
    }

    @Test
    void detectsEveryAdjacentTransposition() {
        SplittableRandom rnd = new SplittableRandom(7);
        for (int sample = 0; sample < 1000; sample++) {
            String id = HealthId.generate(rnd).digits();
            for (int pos = 0; pos + 1 < id.length(); pos++) {
                char a = id.charAt(pos);
                char b = id.charAt(pos + 1);
                if (a == b) {
                    continue;
                }
                String swapped = id.substring(0, pos) + b + a + id.substring(pos + 2);
                assertThat(Verhoeff.isValid(swapped)).as("%s -> %s", id, swapped).isFalse();
            }
        }
    }

    @Test
    void exhaustiveAdjacentTranspositionOverAllDigitPairsAndPositions() {
        // Stronger than random sampling: every (a,b) pair at every position of a 14-digit string.
        String base = "0000000000000";
        for (int pos = 0; pos < 13; pos++) {
            for (int a = 0; a < 10; a++) {
                for (int b = 0; b < 10; b++) {
                    if (a == b) {
                        continue;
                    }
                    char[] payload = (base + "0").toCharArray();
                    payload[pos] = (char) ('0' + a);
                    payload[pos + 1] = (char) ('0' + b);
                    String p13 = new String(payload, 0, 13);
                    String valid = p13 + Verhoeff.checkDigit(p13);
                    char[] sw = valid.toCharArray();
                    if (sw[pos] == sw[pos + 1]) {
                        continue; // only reachable at pos 12 when the check digit equals 'a': no transposition
                    }
                    char t = sw[pos];
                    sw[pos] = sw[pos + 1];
                    sw[pos + 1] = t;
                    assertThat(Verhoeff.isValid(new String(sw))).isFalse();
                }
            }
        }
    }

    @Test
    void rejectsGarbage() {
        assertThat(Verhoeff.isValid(null)).isFalse();
        assertThat(Verhoeff.isValid("")).isFalse();
        assertThat(Verhoeff.isValid("5")).isFalse();
        assertThat(Verhoeff.isValid("12a4")).isFalse();
        assertThatThrownBy(() -> Verhoeff.checkDigit("")).isInstanceOf(IllegalArgumentException.class);
    }
}
