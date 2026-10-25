package com.medigraph.mpi;

import com.medigraph.mpi.algo.JaroWinkler;

import java.util.EnumMap;
import java.util.Map;

/**
 * Computes the Fellegi-Sunter log-likelihood weight of a record pair, field by field (explainable).
 *
 * <p>Complexity: O(F * L^2) per pair for F fields of length L (dominated by Jaro-Winkler), O(F) space for the
 * explanation. {@link BlockingKeys} limits candidates to records sharing a Soundex/birth-year or phone key, so a
 * registration is scored against its blocks only instead of all n records.
 */
public final class FellegiSunterScorer {

    private final MatchConfig config;

    public FellegiSunterScorer(MatchConfig config) {
        this.config = config;
    }

    public record Score(double total, Map<MatchField, Double> perField) {
    }

    public Score score(PatientDemographics a, PatientDemographics b) {
        Map<MatchField, Double> w = new EnumMap<>(MatchField.class);
        w.put(MatchField.GIVEN_NAME, stringWeight(MatchField.GIVEN_NAME, a.givenName(), b.givenName()));
        w.put(MatchField.FAMILY_NAME, stringWeight(MatchField.FAMILY_NAME, a.familyName(), b.familyName()));
        w.put(MatchField.CITY, stringWeight(MatchField.CITY, a.city(), b.city()));
        w.put(MatchField.BIRTH_DATE, exactWeight(MatchField.BIRTH_DATE, a.birthDate(), b.birthDate()));
        w.put(MatchField.GENDER, exactWeight(MatchField.GENDER, lower(a.gender()), lower(b.gender())));
        w.put(MatchField.PHONE, exactWeight(MatchField.PHONE, a.normalizedPhone(), b.normalizedPhone()));
        double total = 0;
        for (double v : w.values()) {
            total += v;
        }
        return new Score(total, w);
    }

    public MatchDecision decide(double weight) {
        if (weight >= config.upperThreshold()) {
            return MatchDecision.AUTO_LINK;
        }
        if (weight >= config.lowerThreshold()) {
            return MatchDecision.REVIEW;
        }
        return MatchDecision.NEW;
    }

    double stringWeight(MatchField field, String x, String y) {
        if (isBlank(x) || isBlank(y)) {
            return 0.0;
        }
        MatchConfig.FieldParams p = config.params(field);
        double sim = JaroWinkler.similarity(x, y);
        if (sim >= config.agreeSimilarity()) {
            return p.agreeWeight();
        }
        if (sim < config.partialFloor()) {
            return p.disagreeWeight();
        }
        double f = (sim - config.partialFloor()) / (config.agreeSimilarity() - config.partialFloor());
        return p.disagreeWeight() + f * (p.agreeWeight() - p.disagreeWeight());
    }

    double exactWeight(MatchField field, Object x, Object y) {
        if (x == null || y == null) {
            return 0.0;
        }
        MatchConfig.FieldParams p = config.params(field);
        return x.equals(y) ? p.agreeWeight() : p.disagreeWeight();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase();
    }
}
