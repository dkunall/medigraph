package com.medigraph.service.prescription.domain;

import com.medigraph.prescription.PrescriptionDecision.Outcome;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One prescribing decision and its issue state. {@code drug} is the resolved catalog name (null if unresolved). */
public record Prescription(UUID id, String patientId, String prescriberId, String facility, String drugInput,
                           String drug, String dosage, Outcome outcome, IssueStatus status, String overrideId,
                           String recordId, Instant createdAt) {

    public Prescription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(prescriberId, "prescriberId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(status, "status");
        if (status == IssueStatus.RECORDED && recordId == null) {
            throw new IllegalArgumentException("a RECORDED prescription needs its record id");
        }
        if (status != IssueStatus.NOT_ISSUED && drug == null) {
            throw new IllegalArgumentException("an issued prescription needs a resolved drug");
        }
    }

    /** Stable per prescription, so a retried record write is replayed by records-service, not duplicated. */
    public String issueIdempotencyKey() {
        return "rx-" + id;
    }

    public Prescription recorded(String newRecordId) {
        if (status == IssueStatus.NOT_ISSUED) {
            throw new IllegalStateException("prescription " + id + " was not approved");
        }
        return new Prescription(id, patientId, prescriberId, facility, drugInput, drug, dosage, outcome,
                IssueStatus.RECORDED, overrideId, newRecordId, createdAt);
    }
}
