package com.medigraph.common.port;

/**
 * A message to deliver after the surrounding transaction commits (transactional outbox). {@code destination}
 * selects the sink: {@link #LEDGER} anchors a ledger entry on the integrity ledger; anything else is a domain event
 * topic. Payloads carry identifiers and hashes only, never clinical content.
 */
public record OutboxMessage(String destination, String aggregateType, String aggregateId, String eventType,
                            String payloadJson) {

    public static final String LEDGER = "ledger";
    public static final String EVENTS = "events";

    public OutboxMessage {
        require(destination, "destination");
        require(aggregateType, "aggregateType");
        require(aggregateId, "aggregateId");
        require(eventType, "eventType");
        require(payloadJson, "payloadJson");
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
