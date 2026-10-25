package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Reference;

public record MedicationRequest(String id, Reference subject, CodeableConcept medicationCodeableConcept, String status,
                                String intent, String dosageText, String authoredOn, String requester)
        implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.MEDICATIONS;
    }

    @JsonIgnore
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
