package com.medigraph.mpi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingKeysTest {

    @Test
    void producesDobYearSoundexAndPhoneKeys() {
        var p = new PatientDemographics("r", "Kunal", "Kumar", LocalDate.of(1999, 3, 1), "male", "+91-98765 43210", "Noida", "A");
        assertThat(BlockingKeys.of(p)).containsExactly("DS:1999:K560", "PH:9876543210");
    }

    @Test
    void spellingVariantsShareTheSoundexBlock() {
        var a = new PatientDemographics("a", "X", "Kumar", LocalDate.of(1999, 3, 1), null, null, null, "A");
        var b = new PatientDemographics("b", "X", "Kumaar", LocalDate.of(1999, 7, 9), null, null, null, "B");
        assertThat(BlockingKeys.of(a)).isEqualTo(BlockingKeys.of(b));
    }

    @Test
    void missingDataProducesFewerKeys() {
        var p = new PatientDemographics("r", "K", null, null, null, "12", null, "A");
        assertThat(BlockingKeys.of(p)).isEmpty();
    }
}
