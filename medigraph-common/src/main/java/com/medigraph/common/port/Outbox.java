package com.medigraph.common.port;

/** Outbound-events port. {@link #enqueue} must be called inside the business transaction it belongs to. */
@FunctionalInterface
public interface Outbox {
    void enqueue(OutboxMessage message);
}
