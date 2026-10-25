package com.medigraph.ledger;

import com.medigraph.ledger.chain.LedgerEntry;

import java.util.List;

/** The Raft command: "seal these entries into the next block at this timestamp". Timestamp is fixed by the
 * proposer so every replica builds a byte-identical block. */
public record BlockProposal(List<LedgerEntry> entries, long proposedAtMillis) {
    public BlockProposal {
        entries = List.copyOf(entries);
    }
}
