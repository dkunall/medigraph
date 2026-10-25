package com.medigraph.service.identity.application;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;

/** Domain events (identifiers only, no demographics) written to the outbox inside the business transaction. */
final class IdentityEvents {

    private final Outbox outbox;

    IdentityEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    void registered(String healthId, String recordId, String decision) {
        emit(healthId, "PatientRegistered",
                "{\"healthId\":\"" + healthId + "\",\"recordId\":\"" + json(recordId) + "\",\"decision\":\"" + decision + "\"}");
    }

    void merged(String survivor, String merged) {
        emit(survivor, "PatientMerged", "{\"healthId\":\"" + survivor + "\",\"mergedHealthId\":\"" + merged + "\"}");
    }

    void erased(String healthId, int registrations) {
        emit(healthId, "PatientDemographicsErased",
                "{\"healthId\":\"" + healthId + "\",\"registrations\":" + registrations + "}");
    }

    private void emit(String healthId, String type, String payload) {
        outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "Patient", healthId, type, payload));
    }

    /** Record ids are {@code FACILITY:MRN} with a restricted alphabet; escape defensively anyway. */
    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
