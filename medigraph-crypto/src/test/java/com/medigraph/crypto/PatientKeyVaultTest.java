package com.medigraph.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientKeyVaultTest {

    private final InMemoryWrappedKeyStore store = new InMemoryWrappedKeyStore();
    private final PatientKeyVault vault = new PatientKeyVault(store, PatientKeyVault.randomKek());
    private final byte[] record = "{\"resourceType\":\"Condition\",\"code\":\"E11.9\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsAndCiphertextHidesPlaintext() {
        EncryptedPayload p = vault.encrypt("p1", "rec-1", record);
        assertThat(new String(p.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("E11.9");
        assertThat(p.iv()).hasSize(12);
        assertThat(p.ciphertext()).hasSize(record.length + 16);
        assertThat(vault.decrypt("p1", "rec-1", p)).isEqualTo(record);
    }

    @Test
    void freshIvPerEncryption() {
        assertThat(vault.encrypt("p1", "r", record).iv()).isNotEqualTo(vault.encrypt("p1", "r", record).iv());
    }

    @Test
    void keysArePerPatientAndCiphertextIsBoundToRecordId() {
        EncryptedPayload p = vault.encrypt("p1", "rec-1", record);
        vault.ensureKey("p2");
        assertThatThrownBy(() -> vault.decrypt("p2", "rec-1", p)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.decrypt("p1", "rec-2", p)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsDetected() {
        EncryptedPayload p = vault.encrypt("p1", "rec-1", record);
        p.ciphertext()[3] ^= 1;
        assertThatThrownBy(() -> vault.decrypt("p1", "rec-1", p)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storedKeyIsWrappedNotRaw() {
        WrappedKey wk = vault.ensureKey("p1");
        assertThat(wk.wrapped()).hasSize(32 + 16); // 256-bit DEK + GCM tag
        PatientKeyVault otherKek = new PatientKeyVault(store, PatientKeyVault.randomKek());
        EncryptedPayload p = vault.encrypt("p1", "r", record);
        assertThatThrownBy(() -> otherKek.decrypt("p1", "r", p)).hasMessageContaining("unwrap");
    }

    @Test
    void cryptoShreddingMakesEveryCopyOfTheCiphertextUnreadable() {
        EncryptedPayload a = vault.encrypt("p1", "rec-1", record);
        EncryptedPayload backupCopy = new EncryptedPayload(a.keyVersion(), a.iv().clone(), a.ciphertext().clone());
        EncryptedPayload otherPatient = vault.encrypt("p2", "rec-9", record);

        assertThat(vault.shred("p1")).isTrue();
        assertThat(vault.isShredded("p1")).isTrue();
        assertThatThrownBy(() -> vault.decrypt("p1", "rec-1", a)).isInstanceOf(KeyShreddedException.class);
        assertThatThrownBy(() -> vault.decrypt("p1", "rec-1", backupCopy)).isInstanceOf(KeyShreddedException.class);
        // a shredded patient cannot silently get a new key and continue
        assertThatThrownBy(() -> vault.encrypt("p1", "rec-2", record)).isInstanceOf(KeyShreddedException.class);
        assertThat(vault.shred("p1")).isFalse();
        // other patients unaffected
        assertThat(vault.decrypt("p2", "rec-9", otherPatient)).isEqualTo(record);
    }

    @Test
    void rejectsShortKek() {
        assertThatThrownBy(() -> new PatientKeyVault(store, new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
