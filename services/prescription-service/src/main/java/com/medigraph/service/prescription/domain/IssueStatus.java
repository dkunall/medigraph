package com.medigraph.service.prescription.domain;

import com.medigraph.prescription.PrescriptionDecision.Outcome;

/**
 * Where an order is in the issue saga. The decision is committed locally first; writing the MedicationRequest into
 * the patient's record is a remote step that can fail and is retried with the same idempotency key.
 */
public enum IssueStatus {
    /** BLOCKED or UNRESOLVED_DRUG: nothing is written to the record. */
    NOT_ISSUED,
    /** Approved and committed here; the record write has not been confirmed yet. */
    PENDING_RECORD,
    /** The MedicationRequest exists in records-service. */
    RECORDED;

    public static IssueStatus after(Outcome o) {
        return switch (o) {
            case APPROVED, APPROVED_WITH_WARNINGS, APPROVED_WITH_OVERRIDE -> PENDING_RECORD;
            case BLOCKED, UNRESOLVED_DRUG -> NOT_ISSUED;
        };
    }
}
