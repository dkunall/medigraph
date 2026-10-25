package com.medigraph.prescription;

public record PrescriptionDecision(Outcome outcome, SafetyReport report, OverrideRecord override) {
    public enum Outcome { APPROVED, APPROVED_WITH_WARNINGS, APPROVED_WITH_OVERRIDE, BLOCKED, UNRESOLVED_DRUG }
}
