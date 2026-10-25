package com.medigraph.service.prescription.adapter.out.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.fhir.MedicationRequest;
import com.medigraph.platform.http.Downstream;
import com.medigraph.service.prescription.application.port.out.MedicationOrders;

import java.util.HashMap;
import java.util.Map;

/** POST the MedicationRequest as the prescriber, with {@code Idempotency-Key}, so retries are replayed. */
public final class RecordsHttpOrders implements MedicationOrders {

    private final Downstream records;

    public RecordsHttpOrders(Downstream records) {
        this.records = records;
    }

    @Override
    public String write(String healthId, MedicationRequest order, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("resourceType", "MedicationRequest");
        body.put("subject", order.subject());
        body.put("medicationCodeableConcept", order.medicationCodeableConcept());
        body.put("status", order.status());
        body.put("intent", order.intent());
        body.put("dosageText", order.dosageText());
        body.put("authoredOn", order.authoredOn());
        body.put("requester", order.requester());
        JsonNode created = records.post("/api/v1/patients/{id}/records", body, JsonNode.class, idempotencyKey, healthId);
        return created.get("recordId").asText();
    }
}
