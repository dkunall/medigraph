package com.medigraph.prescription;

import java.util.List;
import java.util.Optional;

public record SafetyReport(DrugNameResolver.Resolution resolution, List<SafetyAlert> alerts, String notice) {

    public Optional<Severity> highestSeverity() {
        return alerts.stream().map(SafetyAlert::severity).max(Enum::compareTo);
    }

    public boolean requiresOverride() {
        return highestSeverity().map(Severity::blocking).orElse(false);
    }
}
