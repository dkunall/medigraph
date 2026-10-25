package com.medigraph.service.prescription.application;

import com.medigraph.prescription.DrugNameResolver;
import com.medigraph.prescription.PrescriptionDecision.Outcome;
import com.medigraph.prescription.SafetyAlert;
import com.medigraph.service.prescription.domain.IssueStatus;

import java.util.List;
import java.util.UUID;

/** What the prescriber sees: the decision, every alert, exactly what it was checked against, and issue state. */
public record PrescriptionResult(UUID prescriptionId, String healthId, Outcome outcome, IssueStatus status,
                                 DrugNameResolver.Resolution resolution, List<SafetyAlert> alerts,
                                 CheckedAgainst checkedAgainst, String overrideId, String recordId, String notice) {

    public record CheckedAgainst(List<String> currentMedications, List<String> allergies) {
    }

    PrescriptionResult withIssue(IssueStatus newStatus, String newRecordId) {
        return new PrescriptionResult(prescriptionId, healthId, outcome, newStatus, resolution, alerts, checkedAgainst,
                overrideId, newRecordId, notice);
    }
}
