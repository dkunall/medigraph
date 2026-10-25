package com.medigraph.service.records.adapter.out.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.service.records.application.port.out.LedgerAnchor;

/** Serialises the entry into a LEDGER outbox row; the platform relay submits it to ledger-service after commit. */
public final class OutboxLedgerAnchor implements LedgerAnchor {

    private final Outbox outbox;
    private final ObjectMapper json;

    public OutboxLedgerAnchor(Outbox outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public void anchor(LedgerEntry e) {
        try {
            outbox.enqueue(new OutboxMessage(OutboxMessage.LEDGER, "LedgerEntry", e.entryId(), e.type().name(),
                    json.writeValueAsString(e)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialise ledger entry " + e.entryId(), ex);
        }
    }
}
