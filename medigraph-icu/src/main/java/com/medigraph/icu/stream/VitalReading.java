package com.medigraph.icu.stream;

import com.medigraph.icu.Consciousness;
import com.medigraph.icu.VitalType;

import java.util.Objects;

/**
 * One measurement from a bedside device. {@code eventTimeMillis} is when it was measured (device clock), which can
 * differ from arrival order. {@code readingId} lets the processor drop redelivered duplicates (at-least-once input).
 * Booleans and ACVPU are encoded numerically: O2 1/0, consciousness = ordinal.
 */
public record VitalReading(String readingId, String patientId, VitalType type, double value, long eventTimeMillis) {

    public VitalReading {
        Objects.requireNonNull(readingId);
        Objects.requireNonNull(patientId);
        Objects.requireNonNull(type);
    }

    public static VitalReading of(String id, String patient, VitalType type, double value, long t) {
        return new VitalReading(id, patient, type, value, t);
    }

    public static VitalReading consciousness(String id, String patient, Consciousness c, long t) {
        return new VitalReading(id, patient, VitalType.CONSCIOUSNESS, c.ordinal(), t);
    }
}
