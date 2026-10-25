package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Reference;

public record Condition(String id, Reference subject, CodeableConcept code, String clinicalStatus,
                        String onsetDateTime) implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.CONDITIONS;
    }
}
