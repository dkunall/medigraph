package com.medigraph.service.records.application;

import com.medigraph.crypto.KeyEncryptionKeyProvider;
import com.medigraph.crypto.PatientKeyVault;
import com.medigraph.service.records.application.port.out.KeyInventory;

import java.util.List;

/**
 * KEK rotation: rewraps every DEK still wrapped by an old KEK. Records are never re-encrypted (envelope encryption).
 * Keyset-paginated so it is O(n) rows overall with bounded memory, and safe to re-run (already-current keys are skipped).
 */
public final class KeyRotation {

    public record RotationReport(String currentKekId, int examined, int rewrapped) {
    }

    private final KeyInventory inventory;
    private final PatientKeyVault vault;
    private final KeyEncryptionKeyProvider kek;

    public KeyRotation(KeyInventory inventory, PatientKeyVault vault, KeyEncryptionKeyProvider kek) {
        this.inventory = inventory;
        this.vault = vault;
        this.kek = kek;
    }

    public RotationReport rewrapAll(int batchSize) {
        String current = kek.currentKekId();
        int examined = 0;
        int rewrapped = 0;
        String cursor = "";
        while (true) {
            List<String> batch = inventory.wrappedByOtherThan(current, cursor, batchSize);
            if (batch.isEmpty()) {
                return new RotationReport(current, examined, rewrapped);
            }
            for (String patient : batch) {
                examined++;
                rewrapped += vault.rewrap(patient) ? 1 : 0;
            }
            cursor = batch.get(batch.size() - 1);
        }
    }
}
