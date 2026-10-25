package com.medigraph.service.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.id.HealthId;
import com.medigraph.common.security.Role;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.testsupport.Api;
import com.medigraph.testsupport.Api.Resp;
import com.medigraph.testsupport.ServiceProps;
import com.medigraph.testsupport.StubHttpServer;
import com.medigraph.testsupport.StubHttpServer.Reply;
import com.medigraph.testsupport.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentServiceTest {

    /** Fake identity-mpi-service: alias -> canonical. */
    static final Map<String, String> CANONICAL = new ConcurrentHashMap<>();
    static final StubHttpServer IDENTITY = new StubHttpServer().on("/internal/patients/", req -> {
        String id = req.path().substring("/internal/patients/".length());
        String canonical = CANONICAL.get(id);
        if (canonical == null) {
            return new Reply(404, "{\"title\":\"unknown Health ID\"}");
        }
        String aliases = CANONICAL.entrySet().stream().filter(e -> e.getValue().equals(canonical))
                .map(e -> "\"" + e.getKey() + "\"").reduce((a, b) -> a + "," + b).orElseThrow();
        return Reply.ok("{\"healthId\":\"" + canonical + "\",\"aliases\":[" + aliases + "]}");
    });
    static final Path KEY_DIR = tempDir();

    static Path tempDir() {
        try {
            return Files.createTempDirectory("consent-key");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ServiceProps.register(r, "consent_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false");
        r.add("medigraph.consent.identity-url", IDENTITY::url);
        r.add("medigraph.consent.identity-cache-ttl", () -> "0s");
        r.add("medigraph.consent.signing-key-path", () -> KEY_DIR.resolve("consent.pem").toString());
    }

    @TestConfiguration
    static class Tokens {
        @Bean
        ServiceTokenProvider serviceTokenProvider() {
            return () -> TestTokens.service("consent-service");
        }
    }

    @AfterAll
    static void stop() {
        IDENTITY.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private String hid;

    @BeforeEach
    void setUp() {
        api = Api.local(port);
        hid = HealthId.generate().digits();
        CANONICAL.put(hid, hid);
    }

    private Resp grant(String patientToken, String grantee, List<String> cats) {
        return api.post("/api/v1/patients/" + hid + "/consents", Map.of("grantee", grantee, "categories", cats,
                "purpose", "TREATMENT", "durationDays", 30), patientToken);
    }

    private Resp decide(String requester, String facility, String patient, String category) {
        return api.post("/internal/access-decisions", Map.of("requester", requester, "facility", facility,
                "patientId", patient, "category", category, "purpose", "TREATMENT"), TestTokens.service("records-service"));
    }

    private int ledgerRows(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE destination = 'ledger' AND event_type = ? "
                + "AND payload LIKE ?", Integer.class, type, "%" + hid + "%");
    }

    @Test
    void signedConsentGrantsExactlyWhatItSaysAndRevocationEndsIt() {
        Resp g = grant(TestTokens.patient(hid), "dr-a", List.of("MEDICATIONS", "ALLERGIES"));
        assertThat(g.status()).as(g.toString()).isEqualTo(201);
        assertThat(g.json().get("signatureValid").asBoolean()).isTrue();
        String consentId = g.json().at("/consent/consentId").asText();
        assertThat(ledgerRows("CONSENT_GRANTED")).isEqualTo(1);
        // the key file was created on first boot and its public half is published
        assertThat(KEY_DIR.resolve("consent.pem")).exists();
        JsonNode keys = api.get("/api/v1/consent-keys", TestTokens.steward()).json();
        assertThat(keys.get(0).get("keyId").asText()).startsWith("consent-ed25519-")
                .isEqualTo(g.json().at("/consent/signerKeyId").asText());

        assertThat(decide("dr-a", "HOSP-A", hid, "MEDICATIONS").json().get("basis").asText()).isEqualTo("CONSENT");
        assertThat(decide("dr-a", "HOSP-A", hid, "CONDITIONS").json().get("allowed").asBoolean()).isFalse();
        assertThat(decide("dr-b", "HOSP-B", hid, "MEDICATIONS").json().get("allowed").asBoolean()).isFalse();
        assertThat(ledgerRows("ACCESS")).isEqualTo(1);
        assertThat(ledgerRows("ACCESS_DENIED")).isEqualTo(2);

        // only the patient can grant or revoke; the policy endpoint is service-only
        String stranger = HealthId.generate().digits();
        assertThat(grant(TestTokens.patient(stranger), "dr-x", List.of("MEDICATIONS")).status()).isEqualTo(403);
        assertThat(grant(TestTokens.doctor("dr-a", "HOSP-A"), "dr-a", List.of("MEDICATIONS")).status()).isEqualTo(403);
        assertThat(api.delete("/api/v1/consents/" + consentId, TestTokens.patient(stranger)).status()).isEqualTo(403);
        assertThat(api.post("/internal/access-decisions", Map.of(), TestTokens.doctor("dr-a", "HOSP-A")).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/patients/" + hid + "/consents", null).status()).isEqualTo(401);

        Resp revoked = api.delete("/api/v1/consents/" + consentId, TestTokens.patient(hid));
        assertThat(revoked.json().at("/consent/revokedAt").isNull()).isFalse();
        assertThat(decide("dr-a", "HOSP-A", hid, "MEDICATIONS").json().get("allowed").asBoolean()).isFalse();
        assertThat(ledgerRows("CONSENT_REVOKED")).isEqualTo(1);
        assertThat(api.get("/api/v1/patients/" + hid + "/consents", TestTokens.steward()).json()).hasSize(1);
    }

    @Test
    void breakGlassNeedsAReasonIsScopedAndNotifiesThePatient() {
        String nurse = TestTokens.token("nurse-7", "HOSP-ER", Role.NURSE);
        Resp tooShort = api.post("/api/v1/patients/" + hid + "/break-glass", Map.of("reason", "urgent"), nurse);
        assertThat(tooShort.status()).isEqualTo(422);
        assertThat(api.post("/api/v1/patients/" + hid + "/break-glass", Map.of("reason", "unconscious, suspected overdose"),
                TestTokens.token("rc-1", "HOSP-ER", Role.RECEPTION)).status()).isEqualTo(403);

        Resp bg = api.post("/api/v1/patients/" + hid + "/break-glass", Map.of("reason", "unconscious, suspected overdose"), nurse);
        assertThat(bg.status()).as(bg.toString()).isEqualTo(201);
        assertThat(bg.json().get("reasonHash").asText()).hasSize(64);
        assertThat(decide("nurse-7", "HOSP-ER", hid, "ALLERGIES").json().get("basis").asText()).isEqualTo("BREAK_GLASS");
        assertThat(decide("nurse-7", "HOSP-ER", hid, "OBSERVATIONS").json().get("allowed").asBoolean()).isFalse();

        JsonNode inbox = api.get("/api/v1/patients/" + hid + "/notifications", TestTokens.patient(hid)).json();
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("kind").asText()).isEqualTo("BREAK_GLASS");
        // the ledger gets the reason's hash, never the reason itself
        String ledgerPayload = jdbc.queryForObject("SELECT payload FROM outbox_message WHERE event_type = 'BREAK_GLASS' "
                + "AND payload LIKE ?", String.class, "%" + hid + "%");
        assertThat(ledgerPayload).contains(bg.json().get("reasonHash").asText()).doesNotContain("overdose");
    }

    @Test
    void consentGrantedBeforeAnMpiMergeStillAppliesToTheSurvivingIdentity() {
        grant(TestTokens.patient(hid), "HOSP-A", List.of("CONDITIONS"));
        String survivor = HealthId.generate().digits();
        CANONICAL.put(survivor, survivor);
        CANONICAL.put(hid, survivor); // steward merged hid into survivor

        Resp d = decide("dr-z", "HOSP-A", survivor, "CONDITIONS");
        assertThat(d.json().get("basis").asText()).as(d.toString()).isEqualTo("CONSENT");
        // the patient may sign in with either ID
        assertThat(api.get("/api/v1/patients/" + survivor + "/consents", TestTokens.patient(hid)).json()).hasSize(1);
        assertThat(decide("dr-z", "HOSP-A", HealthId.generate().digits(), "CONDITIONS").status()).isEqualTo(404);
        assertThat(Set.of(200, 201)).contains(api.get("/actuator/health/readiness", null).status());
    }
}
