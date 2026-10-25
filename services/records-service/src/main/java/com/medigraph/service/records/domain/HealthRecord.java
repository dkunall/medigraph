package com.medigraph.service.records.domain;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.crypto.EncryptedPayload;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A stored clinical record: ciphertext plus the SHA-256 of its plaintext. The plaintext never leaves the application
 * layer; this type is safe to persist and log (no PHI).
 */
public record HealthRecord(UUID id, String patientId, String resourceType, DataCategory category,
                           EncryptedPayload payload, String contentHash, String ledgerEntryId, String facility,
                           String createdBy, Instant createdAt) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public HealthRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(payload, "payload");
        if (contentHash == null || !SHA256_HEX.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be 64 lowercase hex chars");
        }
    }

    /** Ledger entry id for a record: deterministic, so a retried anchor is deduplicated by the ledger. */
    public static String ledgerEntryIdFor(UUID recordId) {
        return "rec:" + recordId;
    }
}
