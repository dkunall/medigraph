package com.medigraph.ledger.chain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntryTest {

    @Test
    void rejectsFreeTextClinicalDataInMetadata() {
        assertThatThrownBy(() -> new LedgerEntry("e", EntryType.ACCESS, "p", "a", null, 1,
                Map.of("purpose", "patient has stage 2 diabetes")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no free text");
    }

    @Test
    void rejectsUnknownMetadataKeys() {
        assertThatThrownBy(() -> new LedgerEntry("e", EntryType.ACCESS, "p", "a", null, 1, Map.of("diagnosis", "E11")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
    }

    @Test
    void contentHashMustBeSha256Hex() {
        assertThatThrownBy(() -> new LedgerEntry("e", EntryType.RECORD_HASH, "p", "a", "not-a-hash", 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerEntry("e", EntryType.RECORD_HASH, "p", "a", null, 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalEncodingIsIndependentOfMapOrder() {
        LedgerEntry a = new LedgerEntry("e", EntryType.ACCESS, "p", "a", null, 1, Map.of("purpose", "X", "facility", "Y"));
        LedgerEntry b = new LedgerEntry("e", EntryType.ACCESS, "p", "a", null, 1, Map.of("facility", "Y", "purpose", "X"));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }
}
