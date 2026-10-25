package com.medigraph.service.icu.application;

import com.medigraph.icu.VitalType;
import com.medigraph.icu.stream.VitalReading;
import com.medigraph.icu.stream.VitalsStreamProcessor;
import com.medigraph.service.icu.application.port.out.PatientResolver;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Bedside readings in, keyed by canonical Health ID (so a merged patient's monitors land in one window state).
 * Readings timestamped too far in the future are rejected: a device with a skewed clock must not advance the
 * patient's event-time and close windows early.
 */
public final class VitalsIngestion {

    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    public record Reading(String readingId, VitalType type, double value, long eventTimeMillis) {
    }

    private final VitalsStreamProcessor processor;
    private final PatientResolver patients;
    private final Clock clock;

    public VitalsIngestion(VitalsStreamProcessor processor, PatientResolver patients, Clock clock) {
        this.processor = processor;
        this.patients = patients;
        this.clock = clock;
    }

    public int submit(String healthId, List<Reading> readings) {
        long limit = clock.millis() + MAX_CLOCK_SKEW.toMillis();
        for (Reading r : readings) {
            if (r.eventTimeMillis() > limit) {
                throw new IllegalArgumentException("reading " + r.readingId() + " is timestamped in the future");
            }
            if (!Double.isFinite(r.value())) {
                throw new IllegalArgumentException("reading " + r.readingId() + " has a non-finite value");
            }
        }
        String pid = patients.canonical(healthId);
        readings.forEach(r -> processor.submit(new VitalReading(r.readingId(), pid, r.type(), r.value(),
                r.eventTimeMillis())));
        return readings.size();
    }

    /** Closes windows of patients whose monitors went silent (wall-clock watermark). */
    public void advanceWatermark(long nowMillis) {
        processor.advanceTime(nowMillis);
    }
}
