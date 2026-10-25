package com.medigraph.crypto;

/**
 * Adapter stub for HashiCorp Vault's Transit secrets engine (the same shape fits AWS KMS / GCP KMS
 * {@code Encrypt}/{@code Decrypt}). Not implemented and not tested in this build: it documents the contract so
 * that a production adapter can be dropped in behind {@link KeyEncryptionKeyProvider} without touching callers.
 *
 * <p>Intended mapping:
 * <ul>
 *   <li>{@link #wrap}: {@code POST {vault}/v1/transit/encrypt/{keyName}} with
 *       {@code {"plaintext": base64(dek), "context": base64(aad)}} (derived key, so the AAD binds the DEK to the
 *       patient). The response {@code "vault:v<N>:..."} goes into {@link WrappedSecret#ciphertext()};
 *       {@code kekId = keyName + ":v" + N}; {@code iv} is empty (Vault embeds the nonce).</li>
 *   <li>{@link #unwrap}: {@code POST {vault}/v1/transit/decrypt/{keyName}} with the stored ciphertext and context.</li>
 *   <li>Rotation: {@code POST /v1/transit/keys/{keyName}/rotate}; {@code /rewrap} upgrades old wrapped DEKs
 *       without exposing them. {@link PatientKeyVault#rewrap} drives that per patient.</li>
 *   <li>Auth: Kubernetes or AppRole auth to obtain a short-lived token, never a static root token; TLS required.</li>
 * </ul>
 * Every call needs a timeout, a retry with backoff for 5xx/429, and a circuit breaker: a KMS outage must fail
 * record reads/writes closed, never fall back to a local key.
 */
public final class VaultTransitKekProvider implements KeyEncryptionKeyProvider {

    private final String vaultAddress;
    private final String keyName;

    public VaultTransitKekProvider(String vaultAddress, String keyName) {
        this.vaultAddress = vaultAddress;
        this.keyName = keyName;
    }

    @Override
    public String currentKekId() {
        return keyName;
    }

    @Override
    public WrappedSecret wrap(byte[] dek, byte[] aad) {
        throw new UnsupportedOperationException("Vault Transit adapter is a documented stub (" + vaultAddress + ", key "
                + keyName + "); use the local provider in dev");
    }

    @Override
    public byte[] unwrap(WrappedSecret wrapped, byte[] aad) {
        throw new UnsupportedOperationException("Vault Transit adapter is a documented stub");
    }
}
