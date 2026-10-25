package com.medigraph.mpi;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fellegi-Sunter parameters.
 * <ul>
 *   <li>{@code m} = P(field agrees | records are the same person) - data quality.</li>
 *   <li>{@code u} = P(field agrees | different people) - chance agreement (e.g. ~1/365 for full DOB in a year block).</li>
 * </ul>
 * Agreement weight {@code log2(m/u)}, disagreement weight {@code log2((1-m)/(1-u))}; a missing value contributes 0.
 * String fields get a partial weight between the two, linear in Jaro-Winkler similarity within
 * [{@code partialFloor}, {@code agreeSimilarity}).
 */
public record MatchConfig(Map<MatchField, FieldParams> fields, double upperThreshold, double lowerThreshold,
                          double agreeSimilarity, double partialFloor) {

    public record FieldParams(double m, double u) {
        public FieldParams {
            if (m <= 0 || m >= 1 || u <= 0 || u >= 1 || m <= u) {
                throw new IllegalArgumentException("need 0 < u < m < 1, got m=" + m + " u=" + u);
            }
        }

        public double agreeWeight() {
            return log2(m / u);
        }

        public double disagreeWeight() {
            return log2((1 - m) / (1 - u));
        }
    }

    public MatchConfig {
        Objects.requireNonNull(fields);
        if (lowerThreshold > upperThreshold) {
            throw new IllegalArgumentException("lowerThreshold must be <= upperThreshold");
        }
        fields = Map.copyOf(fields);
    }

    /** Literature-typical defaults; tune m/u with EM on labelled pairs in a real deployment. */
    public static MatchConfig defaults() {
        Map<MatchField, FieldParams> f = new EnumMap<>(MatchField.class);
        f.put(MatchField.FAMILY_NAME, new FieldParams(0.95, 0.01));
        f.put(MatchField.GIVEN_NAME, new FieldParams(0.92, 0.02));
        f.put(MatchField.BIRTH_DATE, new FieldParams(0.97, 0.003));
        f.put(MatchField.GENDER, new FieldParams(0.98, 0.5));
        f.put(MatchField.PHONE, new FieldParams(0.80, 0.0005));
        f.put(MatchField.CITY, new FieldParams(0.85, 0.10));
        return new MatchConfig(f, 20.0, 8.0, 0.92, 0.80);
    }

    public FieldParams params(MatchField field) {
        FieldParams p = fields.get(field);
        if (p == null) {
            throw new IllegalStateException("no m/u configured for " + field);
        }
        return p;
    }

    static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
