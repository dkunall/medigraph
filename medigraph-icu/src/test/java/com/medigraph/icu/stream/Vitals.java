package com.medigraph.icu.stream;

import com.medigraph.icu.Consciousness;
import com.medigraph.icu.VitalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Test helper producing a full set of readings at one instant. */
public final class Vitals {
    private static final AtomicLong IDS = new AtomicLong();

    private Vitals() {
    }

    public static List<VitalReading> set(String patient, long t, int rr, int spo2, boolean o2, int sbp, int hr,
                                         Consciousness c, double temp) {
        List<VitalReading> l = new ArrayList<>();
        l.add(VitalReading.of(id(), patient, VitalType.RESPIRATION_RATE, rr, t));
        l.add(VitalReading.of(id(), patient, VitalType.SPO2, spo2, t));
        l.add(VitalReading.of(id(), patient, VitalType.SUPPLEMENTAL_O2, o2 ? 1 : 0, t));
        l.add(VitalReading.of(id(), patient, VitalType.SYSTOLIC_BP, sbp, t));
        l.add(VitalReading.of(id(), patient, VitalType.PULSE, hr, t));
        l.add(VitalReading.consciousness(id(), patient, c, t));
        l.add(VitalReading.of(id(), patient, VitalType.TEMPERATURE, temp, t));
        return l;
    }

    public static List<VitalReading> healthy(String p, long t) {
        return set(p, t, 16, 98, false, 125, 72, Consciousness.ALERT, 36.8);
    }

    public static List<VitalReading> medium(String p, long t) {
        return set(p, t, 22, 95, false, 120, 105, Consciousness.ALERT, 38.5);
    }

    public static List<VitalReading> high(String p, long t) {
        return set(p, t, 26, 91, true, 95, 125, Consciousness.ALERT, 39.4);
    }

    public static String id() {
        return "r" + IDS.incrementAndGet();
    }
}
