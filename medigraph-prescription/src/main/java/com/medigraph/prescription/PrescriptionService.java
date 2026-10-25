package com.medigraph.prescription;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Decision rule: no alerts -> APPROVED; only MINOR/MODERATE -> APPROVED_WITH_WARNINGS; any MAJOR or
 * CONTRAINDICATED -> BLOCKED unless the doctor supplies an override reason, which is audited.
 */
public final class PrescriptionService {

    public static final int MIN_OVERRIDE_REASON = 10;

    private final PrescriptionSafetyChecker checker;
    private final OverrideAuditLog audit;
    private final Clock clock;

    public PrescriptionService(PrescriptionSafetyChecker checker, OverrideAuditLog audit, Clock clock) {
        this.checker = checker;
        this.audit = audit;
        this.clock = clock;
    }

    public PrescriptionDecision prescribe(String patientId, String doctorId, String drug, Collection<String> currentMeds,
                                          Collection<String> allergies, String overrideReason) {
        SafetyReport report = checker.check(drug, currentMeds, allergies);
        if (!report.resolution().resolved()) {
            return new PrescriptionDecision(PrescriptionDecision.Outcome.UNRESOLVED_DRUG, report, null);
        }
        if (report.alerts().isEmpty()) {
            return new PrescriptionDecision(PrescriptionDecision.Outcome.APPROVED, report, null);
        }
        if (!report.requiresOverride()) {
            return new PrescriptionDecision(PrescriptionDecision.Outcome.APPROVED_WITH_WARNINGS, report, null);
        }
        if (overrideReason == null || overrideReason.strip().length() < MIN_OVERRIDE_REASON) {
            return new PrescriptionDecision(PrescriptionDecision.Outcome.BLOCKED, report, null);
        }
        List<SafetyAlert> blocking = report.alerts().stream().filter(a -> a.severity().blocking()).toList();
        OverrideRecord r = new OverrideRecord(UUID.randomUUID().toString(), doctorId, patientId,
                report.resolution().drug(), blocking, overrideReason.strip(), clock.instant());
        audit.record(r);
        return new PrescriptionDecision(PrescriptionDecision.Outcome.APPROVED_WITH_OVERRIDE, report, r);
    }

    public PrescriptionSafetyChecker checker() {
        return checker;
    }
}
