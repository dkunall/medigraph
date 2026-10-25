package com.medigraph.crypto;

/** The patient's data key was destroyed (right to erasure); ciphertext is permanently unreadable. */
public class KeyShreddedException extends RuntimeException {
    public KeyShreddedException(String patientId) {
        super("data key for patient " + patientId + " has been destroyed (crypto-shredded)");
    }
}
