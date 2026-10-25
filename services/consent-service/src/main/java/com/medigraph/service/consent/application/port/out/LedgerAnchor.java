package com.medigraph.service.consent.application.port.out;

import com.medigraph.ledger.chain.LedgerEntry;

/** Queues a ledger entry for the replicated ledger. Must be called inside the business transaction (outbox). */
public interface LedgerAnchor {

    void anchor(LedgerEntry entry);
}
