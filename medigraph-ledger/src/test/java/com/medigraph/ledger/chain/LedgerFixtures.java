package com.medigraph.ledger.chain;

import com.medigraph.common.crypto.Sha256;

import java.util.Map;
import java.util.UUID;

public final class LedgerFixtures {
    private LedgerFixtures() {
    }

    public static LedgerEntry recordHash(String recordJson) {
        return new LedgerEntry(UUID.randomUUID().toString(), EntryType.RECORD_HASH, "patient-1", "dr-a",
                Sha256.hex(recordJson), 1_700_000_000_000L, Map.of("resourceType", "Condition", "facility", "HOSP-A"));
    }

    public static LedgerEntry access(String actor) {
        return new LedgerEntry(UUID.randomUUID().toString(), EntryType.ACCESS, "patient-1", actor, null,
                1_700_000_000_000L, Map.of("purpose", "TREATMENT"));
    }
}
