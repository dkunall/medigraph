package com.medigraph.platform.outbox;

import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Outbox adapter: one INSERT in the caller's transaction. Refuses to run outside a transaction, because an outbox
 * row written in its own transaction would reintroduce exactly the dual-write gap the pattern exists to close.
 */
public final class JdbcOutbox implements Outbox {

    private final JdbcTemplate jdbc;

    public JdbcOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(OutboxMessage m) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox writes must join the business transaction");
        }
        jdbc.update("INSERT INTO outbox_message (destination, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (?, ?, ?, ?, ?)", m.destination(), m.aggregateType(), m.aggregateId(), m.eventType(), m.payloadJson());
    }
}
