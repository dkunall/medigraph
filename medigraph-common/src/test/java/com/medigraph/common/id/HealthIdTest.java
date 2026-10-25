package com.medigraph.common.id;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthIdTest {

    @Test
    void formatsAsFourFourFourTwo() {
        HealthId id = HealthId.parse("91274503186205");
        assertThat(id.formatted()).isEqualTo("9127-4503-1862-05");
        assertThat(id.toString()).matches("\\d{4}-\\d{4}-\\d{4}-\\d{2}");
    }

    @Test
    void parsesFormattedAndBareFormsToSameId() {
        assertThat(HealthId.parse("9127-4503-1862-05")).isEqualTo(HealthId.parse("91274503186205"));
        assertThat(HealthId.parse("9127 4503 1862 05").digits()).isEqualTo("91274503186205");
    }

    @Test
    void rejectsTypos() {
        assertThatThrownBy(() -> HealthId.parse("9127-5403-1862-05")).isInstanceOf(InvalidHealthIdException.class)
                .hasMessageContaining("check digit");
        assertThat(HealthId.isValid("91254503186205")).isFalse();
    }

    @Test
    void rejectsWrongShape() {
        assertThat(HealthId.isValid("1234")).isFalse();
        assertThat(HealthId.isValid("9127-4503-1862-0X")).isFalse();
        assertThat(HealthId.isValid(null)).isFalse();
    }

    @RepeatedTest(5)
    void generatedIdsAreValid14DigitsWithoutLeadingZero() {
        HealthId id = HealthId.generate();
        assertThat(id.digits()).hasSize(14).doesNotStartWith("0");
        assertThat(HealthId.isValid(id.formatted())).isTrue();
    }

    @Test
    void fromPayloadComputesCheckDigit() {
        assertThat(HealthId.fromPayload("9127450318620").digits()).isEqualTo("91274503186205");
    }

    @Test
    void generationIsUniformEnoughToAvoidCollisionsInSmallSample() {
        SplittableRandom rnd = new SplittableRandom(1);
        Set<HealthId> ids = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(HealthId.generate(rnd));
        }
        // 9e12 space: birthday bound gives ~1.4e-4 expected collisions for 50k samples
        assertThat(ids).hasSizeGreaterThan(49_990);
    }
}
