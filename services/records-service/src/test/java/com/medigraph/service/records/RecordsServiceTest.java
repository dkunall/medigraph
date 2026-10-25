package com.medigraph.service.records;

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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * records-service against stubbed identity, consent and ledger services (their own tests cover them; the full
 * multi-service path is in the e2e module). Real Postgres, real HTTP, real JWTs, real AES-GCM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecordsServiceTest {

    /** identity: alias -> canonical */
    static final Map<String, String> CANONICAL = new ConcurrentHashMap<>();
    /** consent: "requester|CATEGORY" -> basis */
    static final Map<String, String> GRANTS = new ConcurrentHashMap<>();
    /** ledger: entryId -> anchored content hash (filled from the outbox, i.e. what the relay would deliver) */
    static final Map<String, String> ANCHORED = new ConcurrentHashMap<>();
    static volatile boolean consentDown;

    static final StubHttpServer IDENTITY = new StubHttpServer().on("/internal/patients/", req -> {
        String rest = req.path().substring("/internal/patients/".length());
        if (rest.endsWith("/erasure")) {
            return Reply.ok("{\"healthId\":\"" + rest.substring(0, 14) + "\",\"registrationsScrubbed\":2}");
        }
        String canonical = CANONICAL.get(rest);
        if (canonical == null) {
            return new Reply(404, "{\"detail\":\"unknown Health ID\"}");
        }
        String aliases = String.join(",", CANONICAL.entrySet().stream().filter(e -> e.getValue().equals(canonical))
                .map(e -> "\"" + e.getKey() + "\"").toList());
        return Reply.ok("{\"healthId\":\"" + canonical + "\",\"aliases\":[" + aliases + "]}");
    });
    static final StubHttpServer CONSENT = new StubHttpServer().on("/internal/access-decisions", req -> {
        if (consentDown) {
            return new Reply(500, "{}");
        }
        JsonNode b = read(req.body());
        String basis = b.get("requester").asText().equals(b.get("patientId").asText()) ? "SELF"
                : GRANTS.getOrDefault(b.get("requester").asText() + "|" + b.get("category").asText(), "DENIED");
        return Reply.ok("{\"allowed\":" + !basis.equals("DENIED") + ",\"basis\":\"" + basis + "\",\"reason\":\"stub\"}");
    });
    static final StubHttpServer LEDGER = new StubHttpServer().on("/internal/ledger/entries/", req -> {
        String rest = req.path().substring("/internal/ledger/entries/".length());
        String entryId = rest.substring(0, rest.lastIndexOf('/'));
        String anchored = ANCHORED.get(entryId);
        if (anchored == null) {
            return new Reply(404, "{\"detail\":\"no such entry\"}");
        }
        if (rest.endsWith("/proof")) {
            return Reply.ok("{\"blockHeight\":3,\"merkleRoot\":\"" + "ab".repeat(32) + "\",\"leafIndex\":0,\"siblings\":[]}");
        }
        boolean ok = req.query().equals("contentHash=" + anchored);
        return Reply.ok("{\"valid\":" + ok + ",\"reason\":\"" + (ok ? "anchored hash matches" : "hash mismatch") + "\"}");
    });

    static JsonNode read(String s) {
        try {
            return Api.JSON.readTree(s);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        ServiceProps.register(r, "records_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false");
        r.add("medigraph.records.identity-url", IDENTITY::url);
        r.add("medigraph.records.consent-url", CONSENT::url);
        r.add("medigraph.ledger.nodes", LEDGER::url);
        r.add("medigraph.records.kek.source", () -> "inline");
        r.add("medigraph.records.kek.current-id", () -> "test-kek");
        r.add("medigraph.records.kek.inline-base64", () -> Base64.getEncoder().encodeToString(kek));
    }

    @TestConfiguration
    static class Tokens {
        @Bean
        ServiceTokenProvider serviceTokenProvider() {
            return () -> TestTokens.service("records-service");
        }
    }

    @AfterAll
    static void stop() {
        IDENTITY.close();
        CONSENT.close();
        LEDGER.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private String hid;
    private final String drA = TestTokens.doctor("dr-a", "HOSP-A");

    @BeforeEach
    void setUp() {
        api = Api.local(port);
        hid = HealthId.generate().digits();
        CANONICAL.put(hid, hid);
        consentDown = false;
        GRANTS.clear();
    }

    private static Map<String, Object> condition(String patient, String text) {
        return Map.of("resourceType", "Condition", "id", "c-" + UUID.randomUUID(),
                "subject", Map.of("reference", "Patient/" + patient), "code", Map.of("text", text),
                "clinicalStatus", "active");
    }

    private static Map<String, Object> allergy(String patient, String text) {
        return Map.of("resourceType", "AllergyIntolerance", "id", "a-" + UUID.randomUUID(),
                "patient", Map.of("reference", "Patient/" + patient), "code", Map.of("text", text), "criticality", "high");
    }

    private void deliverOutboxToLedger() {
        jdbc.queryForList("SELECT payload FROM outbox_message WHERE destination = 'ledger'", String.class).forEach(p -> {
            JsonNode e = read(p);
            if (e.hasNonNull("contentHash")) {
                ANCHORED.put(e.get("entryId").asText(), e.get("contentHash").asText());
            }
        });
    }

    @Test
    void recordsAreEncryptedAnchoredAndReleasedOnlyPerConsentedCategory() {
        Resp created = api.post("/api/v1/patients/" + hid + "/records", condition(hid, "Type 2 diabetes mellitus"), drA);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String recordId = created.json().get("recordId").asText();
        String hash = created.json().get("contentHash").asText();
        assertThat(hash).matches("[0-9a-f]{64}");

        // at rest: ciphertext only, DEK wrapped under the named KEK, ledger + event rows carry no clinical text
        byte[] ct = jdbc.queryForObject("SELECT ciphertext FROM health_record WHERE id = ?::uuid", byte[].class, recordId);
        assertThat(new String(ct, StandardCharsets.ISO_8859_1)).doesNotContain("diabetes");
        assertThat(jdbc.queryForObject("SELECT kek_id FROM patient_key WHERE patient_id = ?", String.class, hid))
                .isEqualTo("test-kek");
        String anchor = jdbc.queryForObject("SELECT payload FROM outbox_message WHERE destination = 'ledger' "
                + "AND aggregate_id = ?", String.class, "rec:" + recordId);
        assertThat(read(anchor).get("type").asText()).isEqualTo("RECORD_HASH");
        assertThat(read(anchor).get("contentHash").asText()).isEqualTo(hash);
        assertThat(jdbc.queryForList("SELECT payload FROM outbox_message WHERE aggregate_id IN (?, ?)", String.class,
                recordId, "rec:" + recordId)).hasSize(2).noneMatch(p -> p.contains("diabetes"));

        GRANTS.put("dr-a|CONDITIONS", "CONSENT");
        Resp chart = api.get("/api/v1/patients/" + hid + "/records", drA);
        assertThat(chart.status()).as(chart.toString()).isEqualTo(200);
        assertThat(chart.json().at("/records/0/resource/code/text").asText()).isEqualTo("Type 2 diabetes mellitus");
        assertThat(chart.json().at("/decisions/CONDITIONS").asText()).isEqualTo("CONSENT");
        assertThat(chart.json().get("withheld").toString()).contains("MEDICATIONS", "ALLERGIES").doesNotContain("CONDITIONS");
        assertThat(CONSENT.received()).allMatch(r -> r.authorization().startsWith("Bearer "));

        // no basis for any requested category -> 403; the patient always sees their own chart
        assertThat(api.get("/api/v1/patients/" + hid + "/records?category=CONDITIONS",
                TestTokens.doctor("dr-b", "HOSP-B")).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/patients/" + hid + "/records?category=CONDITIONS", TestTokens.patient(hid))
                .json().at("/decisions/CONDITIONS").asText()).isEqualTo("SELF");
        assertThat(api.post("/api/v1/patients/" + hid + "/records", condition(hid, "x"),
                TestTokens.token("rc-1", "HOSP-A", Role.RECEPTION)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/patients/" + hid + "/records", null).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/patients/" + hid + "/records", Map.of("resourceType", "Spaceship"), drA).status())
                .isEqualTo(400);
    }

    @Test
    void verificationProvesIntegrityAndTamperingIsDetectedAndQuarantined() {
        String recordId = api.post("/api/v1/patients/" + hid + "/records", allergy(hid, "Penicillin"), drA)
                .json().get("recordId").asText();
        GRANTS.put("dr-a|ALLERGIES", "CONSENT");

        Resp pending = api.get("/api/v1/records/" + recordId + "/verification", drA);
        assertThat(pending.json().get("valid").asBoolean()).isFalse();
        assertThat(pending.json().get("reason").asText()).contains("not yet anchored");

        deliverOutboxToLedger();
        Resp ok = api.get("/api/v1/records/" + recordId + "/verification", drA);
        assertThat(ok.json().get("valid").asBoolean()).as(ok.toString()).isTrue();
        assertThat(ok.json().at("/proof/blockHeight").asInt()).isEqualTo(3);

        // an attacker with DB access flips one ciphertext byte
        jdbc.update("UPDATE health_record SET ciphertext = set_byte(ciphertext, 0, get_byte(ciphertext, 0) # 1) "
                + "WHERE id = ?::uuid", recordId);
        Resp tampered = api.get("/api/v1/records/" + recordId + "/verification", drA);
        assertThat(tampered.json().get("valid").asBoolean()).isFalse();
        assertThat(tampered.json().get("reason").asText()).contains("modified at rest");
        Resp chart = api.get("/api/v1/patients/" + hid + "/records?category=ALLERGIES", drA);
        assertThat(chart.json().get("records")).isEmpty();
        assertThat(chart.json().get("quarantined").get(0).asText()).isEqualTo(recordId);

        assertThat(api.get("/api/v1/records/" + recordId + "/verification", TestTokens.doctor("dr-z", "HOSP-Z")).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/records/" + UUID.randomUUID() + "/verification", drA).status()).isEqualTo(404);
    }

    @Test
    void erasureShredsTheKeysOfEveryMergedAliasAndIsIdempotent() {
        String survivor = HealthId.generate().digits();
        CANONICAL.put(survivor, survivor);
        api.post("/api/v1/patients/" + hid + "/records", condition(hid, "Asthma"), drA);
        CANONICAL.put(hid, survivor); // steward merges hid into survivor
        api.post("/api/v1/patients/" + survivor + "/records", condition(survivor, "Hypertension"), drA);
        GRANTS.put("dr-a|CONDITIONS", "CONSENT");
        assertThat(api.get("/api/v1/patients/" + hid + "/records?category=CONDITIONS", drA).json().get("records")).hasSize(2);

        assertThat(api.delete("/api/v1/patients/" + survivor + "/data",
                TestTokens.patient(HealthId.generate().digits())).status()).isEqualTo(403);
        assertThat(api.delete("/api/v1/patients/" + survivor + "/data", drA).status()).isEqualTo(403);

        Resp erased = api.delete("/api/v1/patients/" + survivor + "/data", TestTokens.patient(hid));
        assertThat(erased.status()).as(erased.toString()).isEqualTo(200);
        assertThat(erased.json().get("keysDestroyed").asInt()).isEqualTo(2);
        assertThat(erased.json().get("registrationsScrubbed").asInt()).isEqualTo(2);
        assertThat(IDENTITY.received()).anyMatch(r -> r.path().equals("/internal/patients/" + survivor + "/erasure")
                && r.method().equals("POST") && r.authorization().startsWith("Bearer "));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE event_type = 'KEY_SHREDDED' "
                + "AND payload LIKE ?", Integer.class, "%" + survivor + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_key WHERE patient_id IN (?, ?)", Integer.class,
                hid, survivor)).isZero();

        Resp gone = api.get("/api/v1/patients/" + survivor + "/records?category=CONDITIONS", drA);
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.json().get("type").asText()).endsWith("crypto-shredded");
        assertThat(api.post("/api/v1/patients/" + survivor + "/records", condition(survivor, "x"), drA).status())
                .isEqualTo(410);
        Resp again = api.delete("/api/v1/patients/" + survivor + "/data", TestTokens.admin());
        assertThat(again.json().get("keysDestroyed").asInt()).isZero();
    }

    @Test
    void readsFailClosedWhenConsentServiceIsDown() {
        api.post("/api/v1/patients/" + hid + "/records", condition(hid, "Migraine"), drA);
        consentDown = true;
        Resp r = api.get("/api/v1/patients/" + hid + "/records?category=CONDITIONS", drA);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.raw().body()).doesNotContain("Migraine");
        assertThat(Set.of(200)).contains(api.get("/actuator/health/liveness", null).status());
        assertThat(List.of(api.get("/v3/api-docs", null).status())).containsAnyOf(200, 401);
    }
}
