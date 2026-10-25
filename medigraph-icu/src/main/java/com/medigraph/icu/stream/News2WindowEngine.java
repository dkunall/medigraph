package com.medigraph.icu.stream;

import com.medigraph.icu.Consciousness;
import com.medigraph.icu.News2Calculator;
import com.medigraph.icu.News2Score;
import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalSigns;
import com.medigraph.icu.VitalType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The event-time semantics shared by every processor implementation (in-memory and Kafka Streams), so both
 * behave identically. All logic is pure over a {@link PatientWindowState}.
 *
 * <ul>
 *   <li>Windows: tumbling, aligned to the epoch, per patient.</li>
 *   <li>Out of order: within a window the value with the latest <i>event</i> time wins, regardless of
 *       arrival order.</li>
 *   <li>Watermark: per patient, {@code maxEventTime - allowedLateness}. A window closes once the watermark
 *       passes its end. Readings for an already-closed window are counted and dropped (late).</li>
 *   <li>Carry-forward: temperature or ACVPU are charted less often than monitor vitals; a value is reused in
 *       later windows while younger than {@code freshness}. Missing parameters yield an incomplete window, not a
 *       guessed score.</li>
 *   <li>Alert dedup/cooldown: alert when risk >= alertAt, unless an alert of the same or higher risk was sent
 *       within {@code cooldown}. Escalation (higher risk than the last alert) always pages immediately.</li>
 * </ul>
 *
 * <p>Complexity: each reading is O(log W) (patient-state lookup plus a TreeMap lookup among the patient's W open windows, then an EnumMap slot); closing a window is pollFirstEntry O(log W) plus scoring a fixed 7 parameters. Space O(P * W) for P active patients and W open windows each (W is bounded by allowedLateness / windowSize + 1).
 */
public final class News2WindowEngine {

    private final StreamConfig cfg;
    private final long size;
    private final long lateness;

    public News2WindowEngine(StreamConfig cfg) {
        this.cfg = cfg;
        this.size = cfg.windowSize().toMillis();
        this.lateness = cfg.allowedLateness().toMillis();
    }

    public long windowStartOf(long t) {
        return Math.floorDiv(t, size) * size;
    }

    public List<WindowScore> onReading(PatientWindowState s, VitalReading r) {
        if (s.seen(r.readingId())) {
            s.duplicatesDropped++;
            return List.of();
        }
        if (r.eventTimeMillis() < s.closedUpTo) {
            s.lateDropped++;
            return List.of();
        }
        Map<VitalType, PatientWindowState.Timed> w = s.windowFor(windowStartOf(r.eventTimeMillis()));
        PatientWindowState.Timed cur = w.get(r.type());
        if (cur == null || r.eventTimeMillis() >= cur.eventTime()) {
            w.put(r.type(), new PatientWindowState.Timed(r.value(), r.eventTimeMillis()));
        }
        s.maxEventTime = Math.max(s.maxEventTime, r.eventTimeMillis());
        return closeUpTo(s, s.maxEventTime - lateness);
    }

    /** Advances time without a reading (silent device): closes windows ending at or before now - lateness. */
    public List<WindowScore> onTime(PatientWindowState s, long nowMillis) {
        return closeUpTo(s, nowMillis - lateness);
    }

    private List<WindowScore> closeUpTo(PatientWindowState s, long watermark) {
        List<WindowScore> out = new ArrayList<>();
        while (!s.openWindows.isEmpty()) {
            long start = s.openWindows.firstKey();
            long end = start + size;
            if (end > watermark) {
                break;
            }
            Map<VitalType, PatientWindowState.Timed> w = s.openWindows.pollFirstEntry().getValue();
            w.forEach((type, tv) -> {
                PatientWindowState.Timed prev = s.lastKnown.get(type);
                if (prev == null || tv.eventTime() >= prev.eventTime()) {
                    s.lastKnown.put(type, tv);
                }
            });
            s.closedUpTo = Math.max(s.closedUpTo, end);
            out.add(score(s, start, end));
        }
        return out;
    }

    private WindowScore score(PatientWindowState s, long start, long end) {
        long freshFrom = end - cfg.freshness().toMillis();
        Map<VitalType, Double> v = new EnumMap<>(VitalType.class);
        Set<VitalType> missing = EnumSet.noneOf(VitalType.class);
        for (VitalType t : VitalType.values()) {
            PatientWindowState.Timed tv = s.lastKnown.get(t);
            if (tv == null || tv.eventTime() < freshFrom) {
                missing.add(t);
            } else {
                v.put(t, tv.value());
            }
        }
        if (!missing.isEmpty()) {
            return new WindowScore(s.patientId, start, end, null, Set.copyOf(missing), false, null);
        }
        News2Score score = News2Calculator.score(new VitalSigns(
                (int) Math.round(v.get(VitalType.RESPIRATION_RATE)),
                (int) Math.round(v.get(VitalType.SPO2)),
                v.get(VitalType.SUPPLEMENTAL_O2) >= 0.5,
                (int) Math.round(v.get(VitalType.SYSTOLIC_BP)),
                (int) Math.round(v.get(VitalType.PULSE)),
                Consciousness.values()[(int) Math.round(v.get(VitalType.CONSCIOUSNESS))],
                v.get(VitalType.TEMPERATURE)));
        String reason = alertDecision(s, score.risk(), end);
        return new WindowScore(s.patientId, start, end, score, Set.of(), reason != null, reason);
    }

    private String alertDecision(PatientWindowState s, RiskLevel risk, long at) {
        if (risk.compareTo(cfg.alertAt()) < 0) {
            return null;
        }
        String reason;
        if (s.lastAlertAt == null) {
            reason = "NEW";
        } else if (risk.compareTo(s.lastAlertRisk) > 0) {
            reason = "ESCALATION";
        } else if (at - s.lastAlertAt >= cfg.cooldown().toMillis()) {
            reason = "REPEAT_AFTER_COOLDOWN";
        } else {
            return null; // dedup: same or lower risk inside the cooldown
        }
        s.lastAlertAt = at;
        s.lastAlertRisk = risk;
        return reason;
    }

    public StreamConfig config() {
        return cfg;
    }
}
