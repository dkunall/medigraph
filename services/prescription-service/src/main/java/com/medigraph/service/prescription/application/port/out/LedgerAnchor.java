package com.medigraph.service.prescription.application.port.out;

import com.medigraph.ledger.chain.LedgerEntry;

/** Must be called inside the business transaction; delivery to the ledger is at-least-once after commit. */
public interface LedgerAnchor {

    void anchor(LedgerEntry entry);
}
