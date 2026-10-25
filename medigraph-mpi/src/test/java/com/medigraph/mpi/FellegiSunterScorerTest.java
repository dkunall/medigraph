package com.medigraph.mpi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FellegiSunterScorerTest {

    private final MatchConfig cfg = MatchConfig.defaults();
    private final FellegiSunterScorer scorer = new FellegiSunterScorer(cfg);

    private static PatientDemographics p(String id, String given, String family, String dob, String g, String phone, String city) {
        return new PatientDemographics(id, given, family, dob == null ? null : LocalDate.parse(dob), g, phone, city, "H");
    }

    @Test
    void weightsFollowLog2Formulas() {
        MatchConfig.FieldParams fp = new MatchConfig.FieldParams(0.9, 0.1);
        assertThat(fp.agreeWeight()).isCloseTo(Math.log(9) / Math.log(2), within(1e-9));
        assertThat(fp.disagreeWeight()).isCloseTo(Math.log(0.1 / 0.9) / Math.log(2), within(1e-9));
    }

    @Test
    void identicalRecordsGetSumOfAgreementWeights() {
        PatientDemographics a = p("1", "Asha", "Verma", "1990-04-12", "female", "+91 98100 00001", "Delhi");
        double expected = 0;
        for (MatchField f : MatchField.values()) {
            expected += cfg.params(f).agreeWeight();
        }
        FellegiSunterScorer.Score s = scorer.score(a, p("2", "Asha", "Verma", "1990-04-12", "F".equals("x") ? "" : "Female", "9810000001", "delhi"));
        assertThat(s.total()).isCloseTo(expected, within(1e-9));
        assertThat(scorer.decide(s.total())).isEqualTo(MatchDecision.AUTO_LINK);
    }

    @Test
    void missingFieldsContributeZero() {
        PatientDemographics a = p("1", "Asha", "Verma", "1990-04-12", null, null, null);
        PatientDemographics b = p("2", "Asha", "Verma", "1990-04-12", "female", "9810000001", "Delhi");
        Map<MatchField, Double> w = scorer.score(a, b).perField();
        assertThat(w.get(MatchField.PHONE)).isZero();
        assertThat(w.get(MatchField.GENDER)).isZero();
        assertThat(w.get(MatchField.CITY)).isZero();
    }

    @Test
    void typoInNameStillAgreesViaJaroWinkler() {
        double w = scorer.stringWeight(MatchField.FAMILY_NAME, "Sharma", "Sharmaa");
        assertThat(w).isEqualTo(cfg.params(MatchField.FAMILY_NAME).agreeWeight());
    }

    @Test
    void partialSimilarityInterpolatesBetweenDisagreeAndAgree() {
        MatchConfig.FieldParams fp = cfg.params(MatchField.GIVEN_NAME);
        double w = scorer.stringWeight(MatchField.GIVEN_NAME, "Dwayne", "Duane"); // JW 0.84
        assertThat(w).isStrictlyBetween(fp.disagreeWeight(), fp.agreeWeight());
    }

    @Test
    void differentPeopleScoreNew() {
        PatientDemographics a = p("1", "Asha", "Verma", "1990-04-12", "female", "9810000001", "Delhi");
        PatientDemographics b = p("2", "Rohan", "Mehta", "1985-01-02", "male", "9000000000", "Pune");
        assertThat(scorer.decide(scorer.score(a, b).total())).isEqualTo(MatchDecision.NEW);
    }

    @Test
    void sameNameAndDobButOtherPhoneAndCityGoesToReview() {
        PatientDemographics a = p("1", "Asha", "Verma", "1990-04-12", "female", "9810000001", "Delhi");
        PatientDemographics b = p("2", "Asha", "Verma", "1990-04-12", "female", "9000000000", "Mumbai");
        assertThat(scorer.decide(scorer.score(a, b).total())).isEqualTo(MatchDecision.REVIEW);
    }

    @Test
    void invalidParamsRejected() {
        assertThatThrownBy(() -> new MatchConfig.FieldParams(0.1, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchConfig(Map.of(), 1, 2, 0.9, 0.8)).isInstanceOf(IllegalArgumentException.class);
    }
}
