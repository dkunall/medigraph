package com.medigraph.crypto;

/** AES-256-GCM output: 96-bit IV + ciphertext with appended 128-bit tag. {@code keyVersion} supports rotation. */
public record EncryptedPayload(int keyVersion, byte[] iv, byte[] ciphertext) {
}
