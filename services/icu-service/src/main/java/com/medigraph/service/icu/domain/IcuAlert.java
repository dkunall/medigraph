package com.medigraph.service.icu.domain;

import com.medigraph.icu.RiskLevel;

import java.time.Instant;
import java.util.Objects;

/** A NEWS2 window that crossed the alerting threshold (after cooldown/de-duplication in the stream). */
public record IcuAlert(String patientId, Instant windowStart, Instant windowEnd, int score, RiskLevel risk,
                       String reason) {

    public IcuAlert {
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(risk, "risk");
        if (score < 0 || score > 20) {
            throw new IllegalArgumentException("NEWS2 total is 0..20, got " + score);
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("window must have positive length");
        }
    }
}
