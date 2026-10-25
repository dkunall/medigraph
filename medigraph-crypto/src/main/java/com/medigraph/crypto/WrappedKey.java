package com.medigraph.crypto;

/**
 * A data-encryption key encrypted under a key-encryption key (envelope encryption). Safe to persist.
 * {@code version} is the DEK version (records carry it); {@code kekId} names the KEK that wrapped it, so rotating
 * the KEK only rewraps this row and never re-encrypts records.
 */
public record WrappedKey(String patientId, int version, String kekId, byte[] iv, byte[] wrapped) {

    public WrappedKey {
        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("DEK version starts at 1");
        }
        if (kekId == null || kekId.isBlank()) {
            throw new IllegalArgumentException("kekId is required");
        }
    }

    KeyEncryptionKeyProvider.WrappedSecret secret() {
        return new KeyEncryptionKeyProvider.WrappedSecret(kekId, iv, wrapped);
    }
}
