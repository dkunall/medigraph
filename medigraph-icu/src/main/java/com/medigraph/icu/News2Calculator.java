package com.medigraph.icu;

import java.util.EnumMap;
import java.util.Map;

/**
 * National Early Warning Score 2 (Royal College of Physicians, 2017), SpO2 Scale 1. Bands:
 * <pre>
 *  parameter        3        2          1           0           1           2          3
 *  resp rate      <=8                9-11       12-20                  21-24       >=25
 *  SpO2 scale 1   <=91     92-93     94-95       >=96
 *  air or O2                 O2                   air
 *  systolic BP    <=90     91-100    101-110     111-219                             >=220
 *  pulse          <=40               41-50       51-90       91-110      111-130     >=131
 *  consciousness                                 Alert                               CVPU
 *  temperature    <=35.0             35.1-36.0   36.1-38.0   38.1-39.0   >=39.1
 * </pre>
 * Not a certified medical device.
 */
public final class News2Calculator {

    private News2Calculator() {
    }

    public static int respirationRate(int rr) {
        if (rr <= 8) return 3;
        if (rr <= 11) return 1;
        if (rr <= 20) return 0;
        if (rr <= 24) return 2;
        return 3;
    }

    public static int spo2Scale1(int spo2) {
        if (spo2 <= 91) return 3;
        if (spo2 <= 93) return 2;
        if (spo2 <= 95) return 1;
        return 0;
    }

    public static int supplementalOxygen(boolean onOxygen) {
        return onOxygen ? 2 : 0;
    }

    public static int systolicBp(int sbp) {
        if (sbp <= 90) return 3;
        if (sbp <= 100) return 2;
        if (sbp <= 110) return 1;
        if (sbp <= 219) return 0;
        return 3;
    }

    public static int pulse(int hr) {
        if (hr <= 40) return 3;
        if (hr <= 50) return 1;
        if (hr <= 90) return 0;
        if (hr <= 110) return 1;
        if (hr <= 130) return 2;
        return 3;
    }

    public static int consciousness(Consciousness c) {
        return c == Consciousness.ALERT ? 0 : 3;
    }

    /** Temperature is recorded to one decimal place; we round first so 35.04 is treated as 35.0. */
    public static int temperature(double celsius) {
        double t = Math.round(celsius * 10) / 10.0;
        if (t <= 35.0) return 3;
        if (t <= 36.0) return 1;
        if (t <= 38.0) return 0;
        if (t <= 39.0) return 1;
        return 2;
    }

    public static News2Score score(VitalSigns v) {
        Map<VitalType, Integer> c = new EnumMap<>(VitalType.class);
        c.put(VitalType.RESPIRATION_RATE, respirationRate(v.respirationRate()));
        c.put(VitalType.SPO2, spo2Scale1(v.spo2()));
        c.put(VitalType.SUPPLEMENTAL_O2, supplementalOxygen(v.supplementalOxygen()));
        c.put(VitalType.SYSTOLIC_BP, systolicBp(v.systolicBp()));
        c.put(VitalType.PULSE, pulse(v.pulse()));
        c.put(VitalType.CONSCIOUSNESS, consciousness(v.consciousness()));
        c.put(VitalType.TEMPERATURE, temperature(v.temperature()));
        int total = c.values().stream().mapToInt(Integer::intValue).sum();
        return new News2Score(total, Map.copyOf(c), risk(total, c.containsValue(3)));
    }

    public static RiskLevel risk(int total, boolean anyThree) {
        if (total >= 7) return RiskLevel.HIGH;
        if (total >= 5) return RiskLevel.MEDIUM;
        if (anyThree) return RiskLevel.LOW_MEDIUM;
        return RiskLevel.LOW;
    }
}
