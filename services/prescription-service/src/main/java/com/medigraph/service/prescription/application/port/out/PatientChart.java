package com.medigraph.service.prescription.application.port.out;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.fhir.FhirResource;
import com.medigraph.consent.Purpose;

import java.util.List;
import java.util.Set;

/** The parts of the chart a safety check needs, read with the prescriber's own consent (token relay). */
public interface PatientChart {

    record Snapshot(String healthId, List<FhirResource> medications, List<FhirResource> allergies,
                    Set<DataCategory> withheld) {
        public Snapshot {
            medications = List.copyOf(medications);
            allergies = List.copyOf(allergies);
            withheld = Set.copyOf(withheld);
        }
    }

    Snapshot medicationsAndAllergies(String healthId, Purpose purpose);
}
