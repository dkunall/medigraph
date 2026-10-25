package com.medigraph.service.records.application;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.service.records.domain.HealthRecord;

/** Domain events (identifiers and hashes only, never clinical content), written inside the business transaction. */
final class RecordEvents {

    private final Outbox outbox;

    RecordEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    void created(HealthRecord r) {
        outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "HealthRecord", r.id().toString(), "RecordCreated",
                "{\"recordId\":\"" + r.id() + "\",\"healthId\":\"" + r.patientId() + "\",\"resourceType\":\""
                        + esc(r.resourceType()) + "\",\"category\":\"" + r.category() + "\",\"contentHash\":\""
                        + r.contentHash() + "\",\"facility\":\"" + esc(r.facility()) + "\"}"));
    }

    /** consent-service (or a notification worker) can consume this to tell the patient. */
    void erased(String healthId, int keysDestroyed) {
        outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "Patient", healthId, "PatientDataShredded",
                "{\"healthId\":\"" + healthId + "\",\"keysDestroyed\":" + keysDestroyed + "}"));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
