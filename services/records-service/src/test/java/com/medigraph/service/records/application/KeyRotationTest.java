package com.medigraph.service.records.application;

import com.medigraph.crypto.EncryptedPayload;
import com.medigraph.crypto.InMemoryWrappedKeyStore;
import com.medigraph.crypto.LocalAesKekProvider;
import com.medigraph.crypto.PatientKeyVault;
import com.medigraph.crypto.WrappedKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class KeyRotationTest {

    @Test
    void rotationRewrapsEveryOldDekInBatchesWithoutTouchingCiphertextAndIsRerunnable() {
        byte[] k1 = PatientKeyVault.randomKek();
        byte[] k2 = PatientKeyVault.randomKek();
        InMemoryWrappedKeyStore store = new InMemoryWrappedKeyStore();
        List<String> patients = IntStream.range(0, 7).mapToObj(i -> String.format("%014d", i)).toList();

        PatientKeyVault before = new PatientKeyVault(store, new LocalAesKekProvider(Map.of("k1", k1), "k1"));
        List<EncryptedPayload> cts = new ArrayList<>();
        for (String p : patients) {
            cts.add(before.encrypt(p, "r-" + p, ("note for " + p).getBytes(StandardCharsets.UTF_8)));
        }

        LocalAesKekProvider rotated = new LocalAesKekProvider(Map.of("k1", k1, "k2", k2), "k2");
        PatientKeyVault after = new PatientKeyVault(store, rotated);
        // inventory over the in-memory store, same contract as the SQL keyset query
        KeyRotation rotation = new KeyRotation((current, afterId, limit) -> patients.stream()
                .filter(p -> p.compareTo(afterId) > 0)
                .filter(p -> store.find(p).map(WrappedKey::kekId).filter(id -> !id.equals(current)).isPresent())
                .limit(limit).toList(), after, rotated);

        KeyRotation.RotationReport report = rotation.rewrapAll(3);
        assertThat(report.rewrapped()).isEqualTo(7);
        assertThat(patients).allMatch(p -> store.find(p).orElseThrow().kekId().equals("k2"));
        // old KEK can now be retired: decrypt works with k2 alone
        PatientKeyVault onlyK2 = new PatientKeyVault(store, new LocalAesKekProvider(Map.of("k2", k2), "k2"));
        for (int i = 0; i < patients.size(); i++) {
            assertThat(new String(onlyK2.decrypt(patients.get(i), "r-" + patients.get(i), cts.get(i)), StandardCharsets.UTF_8))
                    .isEqualTo("note for " + patients.get(i));
        }
        assertThat(rotation.rewrapAll(3).rewrapped()).isZero();
    }
}
