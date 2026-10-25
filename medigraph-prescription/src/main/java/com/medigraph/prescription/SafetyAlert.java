package com.medigraph.prescription;

public record SafetyAlert(Type type, Severity severity, String involving, String message) {
    public enum Type { INTERACTION, ALLERGY, ALLERGY_CROSS_REACTIVITY, DUPLICATE_THERAPY }
}
