package com.medigraph.icu;

import java.util.Map;

public record News2Score(int total, Map<VitalType, Integer> components, RiskLevel risk) {

    public boolean anySingleParameterThree() {
        return components.values().stream().anyMatch(v -> v == 3);
    }
}
