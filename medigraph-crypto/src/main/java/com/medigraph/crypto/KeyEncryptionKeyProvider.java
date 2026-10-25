package com.medigraph.crypto;

/**
 * Port to whatever holds the key-encryption key (KEK): a local AES key in dev, a KMS/HSM or Vault Transit in
 * production. The DEK plaintext only exists in this process for the duration of one operation; the KEK itself never
 * has to leave the provider (a KMS implementation sends the DEK to be wrapped and never sees the KEK).
 *
 * <p>Implementations must be thread-safe. {@link #currentKekId()} names the key used for new wraps; unwrap must
 * accept every key id still listed by the provider so that rotation is a background rewrap, not a flag day.
 */
public interface KeyEncryptionKeyProvider {

    /** Id of the KEK that {@link #wrap} uses now. */
    String currentKekId();

    /** Wraps a DEK under the current KEK, binding it to {@code aad} (authenticated, not encrypted). */
    WrappedSecret wrap(byte[] dek, byte[] aad);

    /**
     * @throws IllegalStateException if the KEK is unknown, the wrapped bytes were modified, or the AAD differs
     */
    byte[] unwrap(WrappedSecret wrapped, byte[] aad);

    /** Output of {@link #wrap}: which KEK, the nonce it used (empty for KMS formats that embed it) and the bytes. */
    record WrappedSecret(String kekId, byte[] iv, byte[] ciphertext) {
        public WrappedSecret {
            if (kekId == null || kekId.isBlank()) {
                throw new IllegalArgumentException("kekId is required");
            }
            iv = iv == null ? new byte[0] : iv;
            if (ciphertext == null || ciphertext.length == 0) {
                throw new IllegalArgumentException("ciphertext is required");
            }
        }
    }
}
