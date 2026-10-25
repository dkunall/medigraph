package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Period;
import com.medigraph.common.fhir.FhirTypes.Reference;

public record Encounter(String id, Reference subject, String status, CodeableConcept type, Period period,
                        String serviceProvider) implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.ENCOUNTERS;
    }
}
