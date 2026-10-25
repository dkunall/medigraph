package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.medigraph.common.fhir.FhirTypes.HumanName;
import com.medigraph.common.fhir.FhirTypes.Identifier;

import java.util.List;

public record Patient(String id, List<Identifier> identifier, HumanName name, String gender, String birthDate,
                      String phone, String city) implements FhirResource {
    @Override
    @JsonIgnore
    public DataCategory category() {
        return DataCategory.DEMOGRAPHICS;
    }
}
