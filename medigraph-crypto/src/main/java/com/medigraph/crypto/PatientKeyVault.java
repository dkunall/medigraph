package com.medigraph.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/**
 * Per-patient envelope encryption with crypto-shredding.
 *
 * <ul>
 *   <li>Each patient gets a random 256-bit DEK. Records are encrypted with AES-256-GCM under that DEK, with the
 *       record id as additional authenticated data (a ciphertext cannot be moved to another record).</li>
 *   <li>DEKs are stored only wrapped by a {@link KeyEncryptionKeyProvider} (local AES key in dev; KMS/Vault in
 *       production), bound to the patient id as AAD so a wrapped key row cannot be swapped between patients.</li>
 *   <li>{@link #rewrap} moves a DEK to the provider's current KEK (rotation) without touching any record.</li>
 *   <li>{@link #shred(String)} deletes the wrapped DEK. Every ciphertext of that patient, including copies in
 *       backups or replicas, becomes undecryptable, while hashes on the immutable ledger remain intact.</li>
 * </ul>
 * Plain DEK bytes are zeroed after each operation (best effort; the JVM may have copied them).
 */
public final class PatientKeyVault {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final WrappedKeyStore store;
    private final KeyEncryptionKeyProvider kek;
    private final SecureRandom random = new SecureRandom();

    public PatientKeyVault(WrappedKeyStore store, KeyEncryptionKeyProvider kek) {
        this.store = store;
        this.kek = kek;
    }

    /** Convenience for tests: a single local KEK. */
    public PatientKeyVault(WrappedKeyStore store, byte[] kekBytes) {
        this(store, LocalAesKekProvider.single(kekBytes));
    }

    public static byte[] randomKek() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** Creates the patient's DEK on first use. Idempotent (first writer wins under concurrency). */
    public WrappedKey ensureKey(String patientId) {
        if (store.isShredded(patientId)) {
            throw new KeyShreddedException(patientId);
        }
        Optional<WrappedKey> existing = store.find(patientId);
        if (existing.isPresent()) {
            return existing.get();
        }
        byte[] dek = newDek();
        try {
            KeyEncryptionKeyProvider.WrappedSecret w = kek.wrap(dek, aadFor(patientId));
            return store.putIfAbsent(new WrappedKey(patientId, 1, w.kekId(), w.iv(), w.ciphertext()));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public EncryptedPayload encrypt(String patientId, String recordId, byte[] plaintext) {
        WrappedKey wk = ensureKey(patientId);
        byte[] dek = kek.unwrap(wk.secret(), aadFor(patientId));
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            byte[] ct = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), iv, recordAad(recordId)).doFinal(plaintext);
            return new EncryptedPayload(wk.version(), iv, ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * @throws KeyShreddedException if the patient's key was destroyed
     * @throws IllegalStateException if the ciphertext or AAD was tampered with (GCM tag mismatch)
     */
    public byte[] decrypt(String patientId, String recordId, EncryptedPayload payload) {
        WrappedKey wk = store.find(patientId).orElseThrow(() -> new KeyShreddedException(patientId));
        byte[] dek = kek.unwrap(wk.secret(), aadFor(patientId));
        try {
            return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), payload.iv(), recordAad(recordId))
                    .doFinal(payload.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed: ciphertext or record binding was modified", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * KEK rotation for one patient: unwrap with the old KEK, wrap with the current one. No-op if already current.
     *
     * @return true if the row was rewrapped
     */
    public boolean rewrap(String patientId) {
        WrappedKey wk = store.find(patientId).orElseThrow(() -> new KeyShreddedException(patientId));
        if (wk.kekId().equals(kek.currentKekId())) {
            return false;
        }
        byte[] dek = kek.unwrap(wk.secret(), aadFor(patientId));
        try {
            KeyEncryptionKeyProvider.WrappedSecret w = kek.wrap(dek, aadFor(patientId));
            return store.replaceWrapping(new WrappedKey(patientId, wk.version(), w.kekId(), w.iv(), w.ciphertext()));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Right to erasure: destroys the DEK. Returns false if there was no key. */
    public boolean shred(String patientId) {
        return store.delete(patientId);
    }

    public boolean isShredded(String patientId) {
        return store.isShredded(patientId);
    }

    private byte[] newDek() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256, random);
            return kg.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key generation failed", e);
        }
    }

    private static Cipher gcm(int mode, SecretKey key, byte[] iv, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        c.updateAAD(aad);
        return c;
    }

    private static byte[] aadFor(String patientId) {
        return ("dek:" + patientId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] recordAad(String recordId) {
        return ("record:" + recordId).getBytes(StandardCharsets.UTF_8);
    }
}
