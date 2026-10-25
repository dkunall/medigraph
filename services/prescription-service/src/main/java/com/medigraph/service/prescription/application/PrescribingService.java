package com.medigraph.service.prescription.application;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.ConflictException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.error.UpstreamUnavailableException;
import com.medigraph.common.fhir.AllergyIntolerance;
import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Reference;
import com.medigraph.common.fhir.MedicationRequest;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.ledger.chain.EntryType;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.prescription.OverrideRecord;
import com.medigraph.prescription.PrescriptionDecision;
import com.medigraph.prescription.PrescriptionService;
import com.medigraph.prescription.Severity;
import com.medigraph.service.prescription.application.PrescriptionResult.CheckedAgainst;
import com.medigraph.service.prescription.application.port.out.LedgerAnchor;
import com.medigraph.service.prescription.application.port.out.MedicationOrders;
import com.medigraph.service.prescription.application.port.out.PatientChart;
import com.medigraph.service.prescription.application.port.out.PrescriptionRepository;
import com.medigraph.service.prescription.domain.IssueStatus;
import com.medigraph.service.prescription.domain.Prescription;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Prescribing as a small saga:
 * <ol>
 *   <li>read active medications and allergies from records-service <em>as the prescriber</em> (their consent applies);
 *       if either category is withheld the check would be incomplete, so prescribing is refused (fail closed);</li>
 *   <li>one local transaction: run the safety checker, persist the decision, the override (if any) with its ledger
 *       anchor, and a {@code PrescriptionDecided} event;</li>
 *   <li>write the MedicationRequest to the record with a per-prescription idempotency key; if records-service is
 *       unavailable the prescription stays {@code PENDING_RECORD} and {@link #issue} retries it safely.</li>
 * </ol>
 * Interaction data is SAMPLE DATA, not clinical advice.
 */
public final class PrescribingService {

    static final Set<DataCategory> REQUIRED = EnumSet.of(DataCategory.MEDICATIONS, DataCategory.ALLERGIES);

    private final PatientChart chart;
    private final PrescriptionService decisions;
    private final PrescriptionRepository prescriptions;
    private final MedicationOrders orders;
    private final LedgerAnchor ledger;
    private final Outbox outbox;
    private final UnitOfWork tx;
    private final Clock clock;

    public PrescribingService(PatientChart chart, PrescriptionService decisions, PrescriptionRepository prescriptions,
                              MedicationOrders orders, LedgerAnchor ledger, Outbox outbox, UnitOfWork tx, Clock clock) {
        this.chart = chart;
        this.decisions = decisions;
        this.prescriptions = prescriptions;
        this.orders = orders;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    public PrescriptionResult prescribe(Actor doctor, PrescribeCommand cmd) {
        if (!doctor.has(Role.DOCTOR)) {
            throw new AccessDeniedException("only doctors prescribe");
        }
        PatientChart.Snapshot snap = chart.medicationsAndAllergies(cmd.healthId(), cmd.purpose());
        Set<DataCategory> missing = snap.withheld().stream().filter(REQUIRED::contains)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DataCategory.class)));
        if (!missing.isEmpty()) {
            throw new AccessDeniedException("a safety check needs medications and allergies; withheld: " + missing);
        }
        List<String> meds = snap.medications().stream()
                .filter(r -> r instanceof MedicationRequest m && m.isActive())
                .map(r -> text(((MedicationRequest) r).medicationCodeableConcept())).filter(Objects::nonNull).toList();
        List<String> allergies = snap.allergies().stream()
                .filter(r -> r instanceof AllergyIntolerance)
                .map(r -> text(((AllergyIntolerance) r).code())).filter(Objects::nonNull).toList();
        String patient = snap.healthId();

        PrescriptionResult decided = tx.inTransaction(() -> {
            PrescriptionDecision d = decisions.prescribe(patient, doctor.id(), cmd.drug(), meds, allergies,
                    cmd.overrideReason());
            UUID id = UUID.randomUUID();
            OverrideRecord o = d.override();
            Prescription p = new Prescription(id, patient, doctor.id(), doctor.facility(), cmd.drug(),
                    d.report().resolution().drug(), cmd.dosage(), d.outcome(), IssueStatus.after(d.outcome()),
                    o == null ? null : o.overrideId(), null, clock.instant());
            prescriptions.insert(p);
            if (o != null) {
                ledger.anchor(new LedgerEntry("override:" + o.overrideId(), EntryType.PRESCRIPTION_OVERRIDE, patient,
                        doctor.id(), Sha256.hex(canonical(o)), clock.millis(), Map.of("drug", code(o.drug()),
                        "severity", d.report().highestSeverity().map(Severity::name).orElse("NONE"),
                        "facility", code(doctor.facility() == null ? "none" : doctor.facility()))));
            }
            outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "Prescription", id.toString(), "PrescriptionDecided",
                    "{\"prescriptionId\":\"" + id + "\",\"healthId\":\"" + patient + "\",\"outcome\":\""
                            + d.outcome() + "\",\"override\":" + (o != null) + "}"));
            return new PrescriptionResult(id, patient, d.outcome(), p.status(), d.report().resolution(),
                    d.report().alerts(), new CheckedAgainst(meds, allergies), p.overrideId(), null, d.report().notice());
        });
        if (decided.status() != IssueStatus.PENDING_RECORD) {
            return decided;
        }
        Prescription p = prescriptions.find(decided.prescriptionId()).orElseThrow();
        return tryRecord(p).map(rec -> decided.withIssue(IssueStatus.RECORDED, rec)).orElse(decided);
    }

    /** Retry the record write of a PENDING_RECORD prescription; a no-op for one already recorded. */
    public Prescription issue(Actor actor, UUID prescriptionId) {
        Prescription p = prescriptions.find(prescriptionId)
                .orElseThrow(() -> new NotFoundException("no prescription " + prescriptionId));
        if (!actor.id().equals(p.prescriberId()) && !actor.has(Role.ADMIN)) {
            throw new AccessDeniedException("only the prescriber (or an admin) may re-issue this prescription");
        }
        return switch (p.status()) {
            case RECORDED -> p;
            case NOT_ISSUED -> throw new ConflictException("prescription " + p.id() + " was " + p.outcome());
            case PENDING_RECORD -> tryRecord(p).map(p::recorded)
                    .orElseThrow(() -> new UpstreamUnavailableException("records-service still unavailable"));
        };
    }

    private java.util.Optional<String> tryRecord(Prescription p) {
        MedicationRequest order = new MedicationRequest(null, Reference.patient(p.patientId()),
                CodeableConcept.of("urn:medigraph:sample-drug", p.drug(), p.drug()), "active", "order", p.dosage(),
                p.createdAt().toString(), p.prescriberId());
        try {
            String recordId = orders.write(p.patientId(), order, p.issueIdempotencyKey());
            prescriptions.markRecorded(p.id(), recordId);
            return java.util.Optional.of(recordId);
        } catch (UpstreamUnavailableException e) {
            return java.util.Optional.empty();
        }
    }

    static String text(CodeableConcept c) {
        if (c == null) {
            return null;
        }
        if (c.text() != null && !c.text().isBlank()) {
            return c.text();
        }
        return c.coding() == null || c.coding().isEmpty() ? null : c.coding().get(0).display();
    }

    /** Hash input for the ledger: fixed field order, so the same override always hashes the same. */
    static String canonical(OverrideRecord o) {
        return String.join("|", o.overrideId(), o.doctorId(), o.patientId(), o.drug(), o.reason(), o.at().toString(),
                o.overriddenAlerts().stream().map(a -> a.type() + ":" + a.severity() + ":" + a.involving())
                        .collect(Collectors.joining(",")));
    }

    /** Ledger metadata is restricted to code-like values. */
    static String code(String s) {
        String c = s.replaceAll("[^A-Za-z0-9_.:,/@+\\-]", "_");
        return c.length() > 64 ? c.substring(0, 64) : c;
    }
}
