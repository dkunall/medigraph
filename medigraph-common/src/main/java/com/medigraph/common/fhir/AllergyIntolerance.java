package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Reference;

public record AllergyIntolerance(String id, Reference patient, CodeableConcept code, String criticality)
        implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.ALLERGIES;
    }
}
