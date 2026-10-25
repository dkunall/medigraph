package com.medigraph.consent;

import com.medigraph.common.fhir.DataCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.medigraph.common.fhir.DataCategory.ALLERGIES;
import static com.medigraph.common.fhir.DataCategory.CONDITIONS;
import static com.medigraph.common.fhir.DataCategory.DEMOGRAPHICS;
import static com.medigraph.common.fhir.DataCategory.ENCOUNTERS;
import static com.medigraph.common.fhir.DataCategory.MEDICATIONS;
import static com.medigraph.common.fhir.DataCategory.OBSERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentServiceTest {

    private static final String PATIENT = "9127-4503-1862-05";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T10:00:00Z"));
    private final InMemoryConsentStore store = new InMemoryConsentStore();
    private final List<String> auditLog = new ArrayList<>();
    private final List<String> notifications = new ArrayList<>();
    private ConsentSigner signer;
    private ConsentService service;

    @BeforeEach
    void setUp() {
        signer = ConsentSigner.generate("consent-key-1");
        ConsentAudit audit = new ConsentAudit() {
            public void consentGranted(ConsentArtefact a) { auditLog.add("GRANTED " + a.consentId()); }
            public void consentRevoked(ConsentArtefact a, String by) { auditLog.add("REVOKED " + a.consentId()); }
            public void breakGlass(BreakGlassGrant g) { auditLog.add("BREAK_GLASS " + g.reasonHash()); }
            public void accessDecided(AccessRequest r, AccessDecision d) { auditLog.add("ACCESS " + d.basis()); }
        };
        service = new ConsentService(store, signer, audit, (p, kind, msg) -> notifications.add(p + ":" + kind),
                clock, Duration.ofHours(4), Duration.ofDays(365));
    }

    private AccessRequest read(String who, String facility, DataCategory c, Purpose p) {
        return new AccessRequest(who, facility, PATIENT, c, p);
    }

    @Test
    void grantedConsentIsSignedAndAllowsOnlyItsCategoriesAndPurpose() {
        ConsentArtefact c = service.grant(PATIENT, "HOSP-A", EnumSet.of(CONDITIONS, MEDICATIONS), Purpose.TREATMENT, Duration.ofDays(30));
        assertThat(service.verifySignature(c)).isTrue();

        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.TREATMENT)).basis())
                .isEqualTo(AccessDecision.Basis.CONSENT);
        assertThat(service.evaluate(read("dr-a", "HOSP-A", OBSERVATIONS, Purpose.TREATMENT)).allowed()).isFalse();
        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.RESEARCH)).allowed()).isFalse();
        assertThat(service.evaluate(read("dr-b", "HOSP-B", CONDITIONS, Purpose.TREATMENT)).allowed()).isFalse();
        assertThat(auditLog).filteredOn(s -> s.startsWith("ACCESS")).hasSize(4); // denials are audited too
    }

    @Test
    void consentCanTargetASingleClinician() {
        service.grant(PATIENT, "dr-x", Set.of(ALLERGIES), Purpose.TREATMENT, Duration.ofDays(1));
        assertThat(service.evaluate(read("dr-x", "HOSP-Z", ALLERGIES, Purpose.TREATMENT)).allowed()).isTrue();
        assertThat(service.evaluate(read("dr-y", "HOSP-Z", ALLERGIES, Purpose.TREATMENT)).allowed()).isFalse();
    }

    @Test
    void revocationTakesEffectImmediatelyAndOnlyThePatientCanRevoke() {
        ConsentArtefact c = service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.TREATMENT, Duration.ofDays(30));
        assertThatThrownBy(() -> service.revoke(c.consentId(), "dr-a")).isInstanceOf(ConsentException.class);
        service.revoke(c.consentId(), PATIENT);
        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.TREATMENT)).allowed()).isFalse();
        assertThatThrownBy(() -> service.revoke(c.consentId(), PATIENT)).hasMessageContaining("already");
        assertThat(auditLog).contains("REVOKED " + c.consentId());
    }

    @Test
    void consentExpires() {
        service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.TREATMENT, Duration.ofHours(2));
        clock.advance(Duration.ofMinutes(119));
        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.TREATMENT)).allowed()).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.TREATMENT)).allowed()).isFalse();
    }

    @Test
    void tamperedConsentRowFailsClosed() {
        ConsentArtefact c = service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.TREATMENT, Duration.ofDays(30));
        // someone widens the scope directly in storage without re-signing
        store.save(new ConsentArtefact(c.consentId(), c.patientId(), c.grantee(), EnumSet.allOf(DataCategory.class),
                c.purpose(), c.issuedAt(), c.expiresAt(), c.signerKeyId(), c.signature(), null));
        assertThat(service.evaluate(read("dr-a", "HOSP-A", OBSERVATIONS, Purpose.TREATMENT)).allowed()).isFalse();
        assertThat(service.evaluate(read("dr-a", "HOSP-A", CONDITIONS, Purpose.TREATMENT)).allowed()).isFalse();
    }

    @Test
    void signatureFromAnotherKeyIsRejected() {
        ConsentArtefact c = service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.TREATMENT, Duration.ofDays(30));
        ConsentSigner other = ConsentSigner.generate("consent-key-1"); // same id, different key
        assertThat(other.verify(c)).isFalse();
    }

    @Test
    void patientMayAlwaysReadOwnRecord() {
        assertThat(service.evaluate(read(PATIENT, null, OBSERVATIONS, Purpose.TREATMENT)).basis())
                .isEqualTo(AccessDecision.Basis.SELF);
    }

    @Test
    void breakGlassNeedsAReasonGivesLimitedTimeBoxedScopeAndNotifiesPatient() {
        assertThatThrownBy(() -> service.breakGlass(PATIENT, "dr-er", "HOSP-B", "urgent"))
                .isInstanceOf(ConsentException.class);
        assertThatThrownBy(() -> service.breakGlass(PATIENT, "dr-er", "HOSP-B", null))
                .isInstanceOf(ConsentException.class);

        BreakGlassGrant g = service.breakGlass(PATIENT, "dr-er", "HOSP-B", "Unconscious after RTA, need allergy history");
        assertThat(g.scope()).containsExactlyInAnyOrder(ALLERGIES, MEDICATIONS, CONDITIONS);
        assertThat(g.reasonHash()).hasSize(64);
        assertThat(auditLog).contains("BREAK_GLASS " + g.reasonHash());
        assertThat(notifications).containsExactly(PATIENT + ":BREAK_GLASS");

        assertThat(service.evaluate(read("dr-er", "HOSP-B", ALLERGIES, Purpose.EMERGENCY)).basis())
                .isEqualTo(AccessDecision.Basis.BREAK_GLASS);
        // limited data: no demographics/encounters/observations
        for (DataCategory c : List.of(DEMOGRAPHICS, ENCOUNTERS, OBSERVATIONS)) {
            assertThat(service.evaluate(read("dr-er", "HOSP-B", c, Purpose.EMERGENCY)).allowed()).isFalse();
        }
        // personal to the requester, not the whole facility
        assertThat(service.evaluate(read("dr-other", "HOSP-B", ALLERGIES, Purpose.EMERGENCY)).allowed()).isFalse();
        clock.advance(Duration.ofHours(4));
        assertThat(service.evaluate(read("dr-er", "HOSP-B", ALLERGIES, Purpose.EMERGENCY)).allowed()).isFalse();
    }

    @Test
    void validatesGrantInput() {
        assertThatThrownBy(() -> service.grant(PATIENT, "HOSP-A", Set.of(), Purpose.TREATMENT, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.TREATMENT, Duration.ofDays(400)))
                .isInstanceOf(ConsentException.class);
        assertThatThrownBy(() -> service.grant(PATIENT, "HOSP-A", Set.of(CONDITIONS), Purpose.EMERGENCY, Duration.ofDays(1)))
                .isInstanceOf(ConsentException.class);
    }
}
