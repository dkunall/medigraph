package com.medigraph.crypto;

import java.util.Optional;

/** Persistence port for wrapped DEKs. {@link #delete} must physically remove the row. */
public interface WrappedKeyStore {

    /** Stores the key if the patient has none yet; returns the stored key (existing or new). */
    WrappedKey putIfAbsent(WrappedKey key);

    Optional<WrappedKey> find(String patientId);

    /** Replaces the wrapping of an existing key (KEK rotation); the DEK and its version are unchanged. */
    boolean replaceWrapping(WrappedKey key);

    boolean delete(String patientId);

    /** True if a key existed and was destroyed (tombstone), distinguishing "shredded" from "never created". */
    boolean isShredded(String patientId);
}
