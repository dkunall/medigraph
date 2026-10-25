package com.medigraph.service.prescription.adapter.out.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.fhir.DataCategory;
import com.medigraph.common.fhir.FhirResource;
import com.medigraph.consent.Purpose;
import com.medigraph.platform.http.Downstream;
import com.medigraph.service.prescription.application.port.out.PatientChart;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * GET records-service chart for MEDICATIONS + ALLERGIES with the caller's token relayed, so consent is evaluated
 * for the doctor, not for this service. A 403 (nothing visible) propagates as AccessDenied.
 */
public final class RecordsHttpChart implements PatientChart {

    private final Downstream records;
    private final ObjectMapper json;

    public RecordsHttpChart(Downstream records, ObjectMapper json) {
        this.records = records;
        this.json = json;
    }

    @Override
    public Snapshot medicationsAndAllergies(String healthId, Purpose purpose) {
        JsonNode chart = records.get("/api/v1/patients/{id}/records?category=MEDICATIONS&category=ALLERGIES&purpose={p}",
                JsonNode.class, healthId, purpose.name());
        List<FhirResource> meds = new ArrayList<>();
        List<FhirResource> allergies = new ArrayList<>();
        for (JsonNode r : chart.path("records")) {
            FhirResource res = decode(r.get("resource"));
            switch (res.category()) {
                case MEDICATIONS -> meds.add(res);
                case ALLERGIES -> allergies.add(res);
                default -> { } // not requested; ignore defensively
            }
        }
        Set<DataCategory> withheld = EnumSet.noneOf(DataCategory.class);
        chart.path("withheld").forEach(c -> withheld.add(DataCategory.valueOf(c.asText())));
        // a quarantined (tampered) record is unknown content, so the check cannot claim to be complete
        if (chart.path("quarantined").size() > 0) {
            withheld.addAll(EnumSet.of(DataCategory.MEDICATIONS, DataCategory.ALLERGIES));
        }
        return new Snapshot(chart.path("healthId").asText(healthId), meds, allergies, withheld);
    }

    private FhirResource decode(JsonNode n) {
        try {
            return json.treeToValue(n, FhirResource.class);
        } catch (IOException e) {
            throw new UncheckedIOException("records-service returned an unreadable resource", e);
        }
    }
}
