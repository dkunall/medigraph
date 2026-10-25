package com.medigraph.service.records.application.port.out;

import java.util.Map;
import java.util.Optional;

/** Read side of the replicated ledger (writes go through the outbox). */
public interface IntegrityLedger {

    record Verification(boolean valid, String reason) {
    }

    /** Checks that {@code contentHash} is what was anchored under {@code entryId}, and that the chain is intact. */
    Verification verify(String entryId, String contentHash);

    /** Merkle inclusion proof, if the entry is already in a sealed block. */
    Optional<Map<String, Object>> proof(String entryId);
}
