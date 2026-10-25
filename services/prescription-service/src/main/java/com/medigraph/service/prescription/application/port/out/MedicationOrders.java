package com.medigraph.service.prescription.application.port.out;

import com.medigraph.common.fhir.MedicationRequest;

/** Writes the order into the patient's record. Must be idempotent per key (retries replay, never duplicate). */
public interface MedicationOrders {

    /** @return the record id assigned by records-service */
    String write(String healthId, MedicationRequest order, String idempotencyKey);
}
