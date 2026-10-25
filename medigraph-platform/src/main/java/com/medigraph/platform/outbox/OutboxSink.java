package com.medigraph.platform.outbox;

import java.util.List;

/**
 * Delivers due outbox rows for one destination. Throwing marks the whole batch for a later retry, so delivery is
 * at-least-once and consumers must be idempotent (ledger: by entry id; events: by outbox id in the key/headers).
 */
public interface OutboxSink {

    String destination();

    void deliver(List<OutboxRecord> batch);
}
