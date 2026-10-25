package com.medigraph.icu.stream;

import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalType;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-patient mutable state. Plain fields so the Kafka Streams adapter can serialise it to its state store as JSON.
 */
public final class PatientWindowState {

    public record Timed(double value, long eventTime) {
    }

    public String patientId;
    /** windowStart -> latest-by-event-time value per parameter, for windows not yet closed. */
    public TreeMap<Long, EnumMap<VitalType, Timed>> openWindows = new TreeMap<>();
    /** Latest value per parameter among closed windows (carried forward while fresh). */
    public EnumMap<VitalType, Timed> lastKnown = new EnumMap<>(VitalType.class);
    public long maxEventTime = Long.MIN_VALUE;
    /** Readings with event time before this belong to closed windows: they are late and dropped. */
    public long closedUpTo = Long.MIN_VALUE;
    public Long lastAlertAt;
    public RiskLevel lastAlertRisk;
    public long lateDropped;
    public long duplicatesDropped;
    public ArrayDeque<String> recentIds = new ArrayDeque<>();
    public HashSet<String> recentIdSet = new HashSet<>();

    public PatientWindowState() {
    }

    public PatientWindowState(String patientId) {
        this.patientId = patientId;
    }

    static final int DEDUP_MEMORY = 2048;

    boolean seen(String readingId) {
        if (recentIdSet.contains(readingId)) {
            return true;
        }
        recentIds.addLast(readingId);
        recentIdSet.add(readingId);
        if (recentIds.size() > DEDUP_MEMORY) {
            recentIdSet.remove(recentIds.removeFirst());
        }
        return false;
    }

    Map<VitalType, Timed> windowFor(long start) {
        return openWindows.computeIfAbsent(start, k -> new EnumMap<>(VitalType.class));
    }
}
