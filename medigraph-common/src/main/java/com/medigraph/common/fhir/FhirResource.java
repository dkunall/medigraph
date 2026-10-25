package com.medigraph.common.fhir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * FHIR R4-shaped clinical resource (simplified). Serialized with a {@code resourceType} discriminator exactly like
 * FHIR JSON, so a payload such as {@code {"resourceType":"Condition", ...}} round-trips.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "resourceType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Encounter.class, name = "Encounter"),
        @JsonSubTypes.Type(value = Condition.class, name = "Condition"),
        @JsonSubTypes.Type(value = MedicationRequest.class, name = "MedicationRequest"),
        @JsonSubTypes.Type(value = Observation.class, name = "Observation"),
        @JsonSubTypes.Type(value = AllergyIntolerance.class, name = "AllergyIntolerance"),
        @JsonSubTypes.Type(value = Patient.class, name = "Patient")
})
public sealed interface FhirResource permits Patient, Encounter, Condition, MedicationRequest, Observation, AllergyIntolerance {

    String id();

    /** Consent is granted per data category, so every resource maps to exactly one. */
    DataCategory category();
}
