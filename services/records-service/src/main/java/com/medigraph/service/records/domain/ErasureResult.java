package com.medigraph.service.records.domain;

/** Outcome of a right-to-erasure request. Re-running it is safe: already-destroyed keys count as 0. */
public record ErasureResult(String healthId, int keysDestroyed, int registrationsScrubbed, String ledgerEntryId) {
}
