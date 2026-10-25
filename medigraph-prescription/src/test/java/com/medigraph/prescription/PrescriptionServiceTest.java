package com.medigraph.prescription;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrescriptionServiceTest {

    private final DrugCatalog catalog = DrugCatalog.loadSample();
    private final PrescriptionSafetyChecker checker = new PrescriptionSafetyChecker(catalog);
    private final InMemoryOverrideAuditLog audit = new InMemoryOverrideAuditLog();
    private final PrescriptionService service = new PrescriptionService(checker, audit,
            Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void flagsInteractionWithCurrentMedication() {
        SafetyReport r = checker.check("aspirin", List.of("warfarin", "metformin"), List.of());
        assertThat(r.alerts()).extracting(SafetyAlert::type, SafetyAlert::involving, SafetyAlert::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SafetyAlert.Type.INTERACTION, "warfarin", Severity.MAJOR));
        assertThat(r.requiresOverride()).isTrue();
    }

    @Test
    void typoIsCorrectedBeforeChecking() {
        SafetyReport r = checker.check("metfromin", List.of("furosemide"), List.of());
        assertThat(r.resolution().drug()).isEqualTo("metformin");
        assertThat(r.alerts()).singleElement().extracting(SafetyAlert::severity).isEqualTo(Severity.MINOR);
    }

    @Test
    void directAllergyByClassIsContraindicated() {
        SafetyReport r = checker.check("amoxicillin", List.of(), List.of("Penicillin"));
        assertThat(r.alerts()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(SafetyAlert.Type.ALLERGY);
            assertThat(a.severity()).isEqualTo(Severity.CONTRAINDICATED);
        });
    }

    @Test
    void crossReactivityFromAllergenDrugName() {
        SafetyReport r = checker.check("cephalexin", List.of(), List.of("amoxicillin"));
        assertThat(r.alerts()).singleElement().extracting(SafetyAlert::type)
                .isEqualTo(SafetyAlert.Type.ALLERGY_CROSS_REACTIVITY);
        assertThat(r.requiresOverride()).isFalse();
    }

    @Test
    void duplicateTherapyIgnoresBroadClasses() {
        assertThat(checker.check("atorvastatin", List.of("simvastatin"), List.of()).alerts())
                .extracting(SafetyAlert::type).containsExactly(SafetyAlert.Type.DUPLICATE_THERAPY);
        assertThat(checker.check("ciprofloxacin", List.of("amoxicillin"), List.of()).alerts()).isEmpty();
    }

    @Test
    void misspelledCurrentMedsAreStillChecked() {
        assertThat(checker.check("tramadol", List.of("sertralin"), List.of()).requiresOverride()).isTrue();
    }

    @Test
    void blocksMajorUntilOverriddenWithReasonAndAuditsTheOverride() {
        PrescriptionDecision blocked = service.prescribe("p1", "dr-a", "sildenafil", List.of("nitroglycerin"), List.of(), null);
        assertThat(blocked.outcome()).isEqualTo(PrescriptionDecision.Outcome.BLOCKED);
        assertThat(service.prescribe("p1", "dr-a", "sildenafil", List.of("nitroglycerin"), List.of(), "ok").outcome())
                .isEqualTo(PrescriptionDecision.Outcome.BLOCKED);
        assertThat(audit.forPatient("p1")).isEmpty();

        PrescriptionDecision ok = service.prescribe("p1", "dr-a", "sildenafil", List.of("nitroglycerin"), List.of(),
                "nitrate stopped 48h ago, cardiology consulted");
        assertThat(ok.outcome()).isEqualTo(PrescriptionDecision.Outcome.APPROVED_WITH_OVERRIDE);
        assertThat(audit.forPatient("p1")).singleElement().satisfies(o -> {
            assertThat(o.doctorId()).isEqualTo("dr-a");
            assertThat(o.overriddenAlerts()).extracting(SafetyAlert::severity).containsExactly(Severity.CONTRAINDICATED);
        });
    }

    @Test
    void warningsDoNotBlockAndCleanPrescriptionsApprove() {
        assertThat(service.prescribe("p", "d", "clopidogrel", List.of("omeprazole"), List.of(), null).outcome())
                .isEqualTo(PrescriptionDecision.Outcome.APPROVED_WITH_WARNINGS);
        assertThat(service.prescribe("p", "d", "paracetamol", List.of("metformin"), List.of(), null).outcome())
                .isEqualTo(PrescriptionDecision.Outcome.APPROVED);
        assertThat(service.prescribe("p", "d", "qqqqqqq", List.of(), List.of(), null).outcome())
                .isEqualTo(PrescriptionDecision.Outcome.UNRESOLVED_DRUG);
    }
}
