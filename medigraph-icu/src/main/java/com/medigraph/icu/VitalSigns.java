package com.medigraph.icu;

/** A complete set of the seven NEWS2 parameters. SpO2 is interpreted on Scale 1. */
public record VitalSigns(int respirationRate, int spo2, boolean supplementalOxygen, int systolicBp, int pulse,
                         Consciousness consciousness, double temperature) {
}
