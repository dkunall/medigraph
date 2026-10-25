package com.medigraph.prescription;

import java.time.Instant;
import java.util.List;

public record OverrideRecord(String overrideId, String doctorId, String patientId, String drug,
                             List<SafetyAlert> overriddenAlerts, String reason, Instant at) {
}
