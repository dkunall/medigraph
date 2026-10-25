package com.medigraph.platform.outbox;

/** An outbox row as seen by the relay and its sinks. */
public record OutboxRecord(long id, String destination, String aggregateType, String aggregateId, String eventType,
                           String payload, int attempts) {
}
