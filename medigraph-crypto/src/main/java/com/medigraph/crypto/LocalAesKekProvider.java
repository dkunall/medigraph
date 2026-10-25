package com.medigraph.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * KEKs held in process memory as AES-256 keys; wraps with AES-256-GCM (96-bit random nonce, 128-bit tag).
 * Suitable for development and tests. For production use a KMS/HSM-backed provider such as
 * {@link VaultTransitKekProvider}; the key material here is only as safe as the process and its environment.
 *
 * <p>Several keys may be loaded for rotation: new wraps use {@code currentKekId}; older ids still unwrap.
 */
public final class LocalAesKekProvider implements KeyEncryptionKeyProvider {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final Map<String, SecretKeySpec> keys;
    private final String currentKekId;
    private final SecureRandom random = new SecureRandom();

    public LocalAesKekProvider(Map<String, byte[]> keysById, String currentKekId) {
        if (keysById.isEmpty() || !keysById.containsKey(currentKekId)) {
            throw new IllegalArgumentException("current KEK id '" + currentKekId + "' is not among the loaded keys");
        }
        Map<String, SecretKeySpec> copy = new LinkedHashMap<>();
        keysById.forEach((id, k) -> {
            if (k == null || k.length != 32) {
                throw new IllegalArgumentException("KEK must be 256 bits (kek id " + id + ")");
            }
            copy.put(id, new SecretKeySpec(k.clone(), "AES"));
        });
        this.keys = Map.copyOf(copy);
        this.currentKekId = currentKekId;
    }

    /** Single-key convenience (id {@code "local-1"}). */
    public static LocalAesKekProvider single(byte[] kek) {
        return new LocalAesKekProvider(Map.of("local-1", kek), "local-1");
    }

    @Override
    public String currentKekId() {
        return currentKekId;
    }

    public Set<String> kekIds() {
        return keys.keySet();
    }

    @Override
    public WrappedSecret wrap(byte[] dek, byte[] aad) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            return new WrappedSecret(currentKekId, iv, cipher(Cipher.ENCRYPT_MODE, keys.get(currentKekId), iv, aad).doFinal(dek));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot wrap data key", e);
        }
    }

    @Override
    public byte[] unwrap(WrappedSecret w, byte[] aad) {
        SecretKeySpec kek = keys.get(w.kekId());
        if (kek == null) {
            throw new IllegalStateException("cannot unwrap data key: KEK '" + w.kekId() + "' is not loaded");
        }
        try {
            return cipher(Cipher.DECRYPT_MODE, kek, w.iv(), aad).doFinal(w.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot unwrap data key (wrong KEK or tampered key row)", e);
        }
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] iv, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        c.updateAAD(aad);
        return c;
    }
}
