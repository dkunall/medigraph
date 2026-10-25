package com.medigraph.service.consent.adapter.out.keys;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.consent.ConsentArtefact;
import com.medigraph.consent.ConsentSigner;
import com.medigraph.consent.Purpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemConsentKeyFileTest {

    @TempDir
    Path dir;

    @Test
    void generatedKeySurvivesARestartWithTheSameIdAndOwnerOnlyPermissions() throws Exception {
        Path pem = dir.resolve("keys/consent.pem");
        PemConsentKeyFile.LoadedKey first = PemConsentKeyFile.loadOrCreate(pem, true);
        PemConsentKeyFile.LoadedKey again = PemConsentKeyFile.loadOrCreate(pem, true);
        assertThat(first.generated()).isTrue();
        assertThat(again.generated()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(pem))).isEqualTo("rw-------");

        // an artefact signed before the "restart" verifies with the reloaded key
        ConsentSigner before = new ConsentSigner(first.id().value(), first.pair());
        ConsentArtefact signed = before.sign(new ConsentArtefact("c-1", "12345678901234", "dr-a",
                Set.of(DataCategory.MEDICATIONS), Purpose.TREATMENT, Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                null, null, null));
        assertThat(new ConsentSigner(again.id().value(), again.pair()).verify(signed)).isTrue();
    }

    @Test
    void rotatedSignerStillVerifiesOldArtefactsOnlyWhenTheOldKeyIsTrusted() {
        PemConsentKeyFile.LoadedKey old = PemConsentKeyFile.loadOrCreate(dir.resolve("old.pem"), true);
        PemConsentKeyFile.LoadedKey rotated = PemConsentKeyFile.loadOrCreate(dir.resolve("new.pem"), true);
        ConsentArtefact signed = new ConsentSigner(old.id().value(), old.pair()).sign(new ConsentArtefact("c-2",
                "12345678901234", "dr-a", Set.of(DataCategory.ALLERGIES), Purpose.TREATMENT, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), null, null, null));
        ConsentSigner current = new ConsentSigner(rotated.id().value(), rotated.pair());
        assertThat(current.verify(signed)).isFalse();
        current.trust(old.id().value(), old.pair().getPublic());
        assertThat(current.verify(signed)).isTrue();
    }

    @Test
    void missingKeyFailsFastWhenGenerationIsDisabledAndCorruptFilesAreRejected() throws Exception {
        assertThatThrownBy(() -> PemConsentKeyFile.loadOrCreate(dir.resolve("absent.pem"), false))
                .hasMessageContaining("generation disabled");
        Path bad = Files.writeString(dir.resolve("bad.pem"), "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertThatThrownBy(() -> PemConsentKeyFile.loadOrCreate(bad, true)).isInstanceOf(IllegalStateException.class);
    }
}
