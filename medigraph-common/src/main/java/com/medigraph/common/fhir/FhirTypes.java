package com.medigraph.common.fhir;

import java.util.List;

/** Small FHIR R4 datatypes, simplified (only the fields MediGraph uses). */
public final class FhirTypes {

    private FhirTypes() {
    }

    public record Coding(String system, String code, String display) {
    }

    public record CodeableConcept(List<Coding> coding, String text) {
        public static CodeableConcept of(String system, String code, String display) {
            return new CodeableConcept(List.of(new Coding(system, code, display)), display);
        }

        /** First code, or {@code text} when no coding is present. */
        public String primaryCode() {
            return coding != null && !coding.isEmpty() ? coding.get(0).code() : text;
        }
    }

    /** {@code "Patient/<id>"}-style literal reference. */
    public record Reference(String reference) {
        public static Reference patient(String id) {
            return new Reference("Patient/" + id);
        }
    }

    public record Identifier(String system, String value) {
    }

    public record HumanName(String family, List<String> given) {
        public String firstGiven() {
            return given == null || given.isEmpty() ? "" : given.get(0);
        }
    }

    public record Quantity(double value, String unit) {
    }

    public record Period(String start, String end) {
    }
}
