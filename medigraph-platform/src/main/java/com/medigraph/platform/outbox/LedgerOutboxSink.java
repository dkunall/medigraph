package com.medigraph.platform.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.platform.ledger.LedgerClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends ledger-bound outbox rows to ledger-service as one batch (one Raft proposal / one block per relay poll).
 * The idempotency key is derived from the row ids, and the ledger also skips entry ids it already holds, so a
 * relay crash between "ledger committed" and "row stamped" only causes a harmless re-send.
 */
public final class LedgerOutboxSink implements OutboxSink {

    private final LedgerClient ledger;
    private final ObjectMapper json;
    private final String service;

    public LedgerOutboxSink(LedgerClient ledger, ObjectMapper json, String service) {
        this.ledger = ledger;
        this.json = json;
        this.service = service;
    }

    @Override
    public String destination() {
        return OutboxMessage.LEDGER;
    }

    @Override
    public void deliver(List<OutboxRecord> batch) {
        List<JsonNode> entries = new ArrayList<>(batch.size());
        for (OutboxRecord r : batch) {
            try {
                entries.add(json.readTree(r.payload()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("outbox row " + r.id() + " is not JSON", e);
            }
        }
        String key = service + "-outbox-" + batch.get(0).id() + "-" + batch.get(batch.size() - 1).id() + "-" + batch.size();
        ledger.submit(entries, key);
    }
}
