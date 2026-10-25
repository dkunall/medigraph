package com.medigraph.service.records.adapter.out.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.platform.ledger.LedgerClient;
import com.medigraph.service.records.application.port.out.IntegrityLedger;

import java.util.Map;
import java.util.Optional;

/** Reads from ledger-service (leader-following client over the 3 nodes). An entry not yet committed is "not found". */
public final class HttpIntegrityLedger implements IntegrityLedger {

    private final LedgerClient ledger;
    private final ObjectMapper json;

    public HttpIntegrityLedger(LedgerClient ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @Override
    public Verification verify(String entryId, String contentHash) {
        try {
            LedgerClient.Verification v = ledger.verifyContentHash(entryId, contentHash);
            return new Verification(v.valid(), v.reason());
        } catch (NotFoundException e) {
            return new Verification(false, "not yet anchored on the ledger (outbox delivery pending)");
        }
    }

    @Override
    public Optional<Map<String, Object>> proof(String entryId) {
        try {
            return Optional.of(json.convertValue(ledger.proof(entryId), new TypeReference<Map<String, Object>>() {
            }));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
