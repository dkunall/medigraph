package com.medigraph.icu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Band edges from the RCP NEWS2 chart, both sides of every boundary. */
class News2CalculatorTest {

    @ParameterizedTest
    @CsvSource({"0,3", "8,3", "9,1", "11,1", "12,0", "20,0", "21,2", "24,2", "25,3", "40,3"})
    void respirationRate(int rr, int expected) {
        assertThat(News2Calculator.respirationRate(rr)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"85,3", "91,3", "92,2", "93,2", "94,1", "95,1", "96,0", "100,0"})
    void spo2Scale1(int s, int expected) {
        assertThat(News2Calculator.spo2Scale1(s)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"70,3", "90,3", "91,2", "100,2", "101,1", "110,1", "111,0", "219,0", "220,3"})
    void systolic(int bp, int expected) {
        assertThat(News2Calculator.systolicBp(bp)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"30,3", "40,3", "41,1", "50,1", "51,0", "90,0", "91,1", "110,1", "111,2", "130,2", "131,3"})
    void pulse(int hr, int expected) {
        assertThat(News2Calculator.pulse(hr)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"34.0,3", "35.0,3", "35.04,3", "35.1,1", "36.0,1", "36.1,0", "38.0,0", "38.1,1", "39.0,1", "39.1,2", "41,2"})
    void temperature(double t, int expected) {
        assertThat(News2Calculator.temperature(t)).isEqualTo(expected);
    }

    @Test
    void oxygenAndConsciousness() {
        assertThat(News2Calculator.supplementalOxygen(true)).isEqualTo(2);
        assertThat(News2Calculator.supplementalOxygen(false)).isZero();
        assertThat(News2Calculator.consciousness(Consciousness.ALERT)).isZero();
        for (Consciousness c : new Consciousness[]{Consciousness.CONFUSION, Consciousness.VOICE, Consciousness.PAIN, Consciousness.UNRESPONSIVE}) {
            assertThat(News2Calculator.consciousness(c)).isEqualTo(3);
        }
    }

    @Test
    void healthyAdultScoresZero() {
        News2Score s = News2Calculator.score(new VitalSigns(16, 98, false, 125, 72, Consciousness.ALERT, 36.8));
        assertThat(s.total()).isZero();
        assertThat(s.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void singleRedParameterIsLowMedium() {
        News2Score s = News2Calculator.score(new VitalSigns(16, 98, false, 125, 72, Consciousness.CONFUSION, 36.8));
        assertThat(s.total()).isEqualTo(3);
        assertThat(s.risk()).isEqualTo(RiskLevel.LOW_MEDIUM);
    }

    @Test
    void mediumAndHighAggregate() {
        // RR 22 (2) + SpO2 95 (1) + pulse 105 (1) + temp 38.5 (1) = 5
        assertThat(News2Calculator.score(new VitalSigns(22, 95, false, 120, 105, Consciousness.ALERT, 38.5)).risk())
                .isEqualTo(RiskLevel.MEDIUM);
        // septic picture: RR 26 (3) + SpO2 91 (3) + O2 (2) + SBP 95 (2) + HR 125 (2) + temp 39.4 (2) = 14
        News2Score s = News2Calculator.score(new VitalSigns(26, 91, true, 95, 125, Consciousness.ALERT, 39.4));
        assertThat(s.total()).isEqualTo(14);
        assertThat(s.risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void aggregateFourWithoutAThreeIsLow() {
        News2Score s = News2Calculator.score(new VitalSigns(22, 95, false, 108, 72, Consciousness.ALERT, 36.8));
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.risk()).isEqualTo(RiskLevel.LOW);
    }
}
