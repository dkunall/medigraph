package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Quantity;
import com.medigraph.common.fhir.FhirTypes.Reference;

public record Observation(String id, Reference subject, String status, CodeableConcept code, Quantity valueQuantity,
                          String effectiveDateTime) implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.OBSERVATIONS;
    }
}
