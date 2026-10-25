package com.medigraph.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KekProviderTest {

    private static String b64(byte[] k) {
        return Base64.getEncoder().encodeToString(k);
    }

    private final byte[] record = "{\"resourceType\":\"Observation\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void kekRotationRewrapsTheDekWithoutReEncryptingRecords() {
        byte[] k1 = PatientKeyVault.randomKek();
        byte[] k2 = PatientKeyVault.randomKek();
        InMemoryWrappedKeyStore store = new InMemoryWrappedKeyStore();
        PatientKeyVault before = new PatientKeyVault(store, new LocalAesKekProvider(Map.of("k1", k1), "k1"));
        EncryptedPayload p = before.encrypt("p1", "rec-1", record);
        assertThat(store.find("p1").orElseThrow().kekId()).isEqualTo("k1");

        PatientKeyVault after = new PatientKeyVault(store, new LocalAesKekProvider(Map.of("k1", k1, "k2", k2), "k2"));
        assertThat(after.decrypt("p1", "rec-1", p)).isEqualTo(record); // old KEK still unwraps
        assertThat(after.rewrap("p1")).isTrue();
        assertThat(after.rewrap("p1")).isFalse();
        assertThat(store.find("p1").orElseThrow().kekId()).isEqualTo("k2");

        PatientKeyVault k2Only = new PatientKeyVault(store, new LocalAesKekProvider(Map.of("k2", k2), "k2"));
        assertThat(k2Only.decrypt("p1", "rec-1", p)).isEqualTo(record); // record untouched, DEK now under k2
    }

    @Test
    void wrappedKeyIsBoundToItsPatient() {
        InMemoryWrappedKeyStore store = new InMemoryWrappedKeyStore();
        PatientKeyVault v = new PatientKeyVault(store, PatientKeyVault.randomKek());
        WrappedKey p1 = v.ensureKey("p1");
        store.putIfAbsent(new WrappedKey("p2", 1, p1.kekId(), p1.iv(), p1.wrapped())); // attacker copies the row
        assertThatThrownBy(() -> v.encrypt("p2", "r", record)).hasMessageContaining("unwrap");
    }

    @Test
    void environmentProviderLoadsAllKeysAndTheCurrentOne() {
        LocalAesKekProvider p = KekProviders.fromEnvironment(Map.of(
                "MEDIGRAPH_KEK_A", b64(PatientKeyVault.randomKek()),
                "MEDIGRAPH_KEK_B", b64(PatientKeyVault.randomKek()),
                "MEDIGRAPH_KEK_CURRENT", "B",
                "PATH", "/usr/bin"));
        assertThat(p.kekIds()).containsExactlyInAnyOrder("a", "b");
        assertThat(p.currentKekId()).isEqualTo("b");
        assertThatThrownBy(() -> KekProviders.fromEnvironment(Map.of("MEDIGRAPH_KEK_A", b64(PatientKeyVault.randomKek()))))
                .hasMessageContaining("MEDIGRAPH_KEK_CURRENT");
    }

    @Test
    void directoryProviderReadsOneKeyPerFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2026-01"), b64(PatientKeyVault.randomKek()) + "\n");
        Files.writeString(dir.resolve("2026-07"), b64(PatientKeyVault.randomKek()));
        LocalAesKekProvider p = KekProviders.fromDirectory(dir, "2026-07");
        assertThat(p.kekIds()).containsExactlyInAnyOrder("2026-01", "2026-07");
        byte[] dek = PatientKeyVault.randomKek();
        assertThat(p.unwrap(p.wrap(dek, "aad".getBytes()), "aad".getBytes())).isEqualTo(dek);
        assertThatThrownBy(() -> KekProviders.fromDirectory(dir, "missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongKeySizesAndUnknownKekIds() {
        assertThatThrownBy(() -> KekProviders.fromBase64("x", b64(new byte[16]))).hasMessageContaining("256 bits");
        LocalAesKekProvider p = LocalAesKekProvider.single(PatientKeyVault.randomKek());
        KeyEncryptionKeyProvider.WrappedSecret w = p.wrap(new byte[32], new byte[0]);
        assertThatThrownBy(() -> p.unwrap(new KeyEncryptionKeyProvider.WrappedSecret("other", w.iv(), w.ciphertext()), new byte[0]))
                .hasMessageContaining("not loaded");
    }

    @Test
    void vaultStubFailsClosed() {
        VaultTransitKekProvider v = new VaultTransitKekProvider("https://vault.internal:8200", "medigraph-kek");
        assertThat(v.currentKekId()).isEqualTo("medigraph-kek");
        assertThatThrownBy(() -> v.wrap(new byte[32], new byte[0])).isInstanceOf(UnsupportedOperationException.class);
    }
}
