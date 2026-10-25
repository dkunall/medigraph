package com.medigraph.service.prescription;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.id.HealthId;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** prescription-service against a stubbed records-service (which has its own tests). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrescriptionServiceTest {

    record Chart(List<String> meds, List<String> allergies, List<String> withheld) {
    }

    static final Map<String, Chart> CHARTS = new ConcurrentHashMap<>();
    static final AtomicBoolean RECORDS_WRITES_DOWN = new AtomicBoolean();

    static final StubHttpServer RECORDS = new StubHttpServer().on("/api/v1/patients/", req -> {
        String hid = req.path().substring("/api/v1/patients/".length(), "/api/v1/patients/".length() + 14);
        if (req.method().equals("POST")) {
            return RECORDS_WRITES_DOWN.get() ? new Reply(503, "{\"detail\":\"down\"}")
                    : new Reply(201, "{\"recordId\":\"" + UUID.nameUUIDFromBytes(req.body().getBytes()) + "\"}");
        }
        Chart c = CHARTS.get(hid);
        StringBuilder records = new StringBuilder();
        for (String m : c.meds()) {
            records.append(records.isEmpty() ? "" : ",").append("{\"category\":\"MEDICATIONS\",\"resource\":"
                    + "{\"resourceType\":\"MedicationRequest\",\"status\":\"active\",\"medicationCodeableConcept\":"
                    + "{\"text\":\"" + m + "\"}}}");
        }
        for (String a : c.allergies()) {
            records.append(records.isEmpty() ? "" : ",").append("{\"category\":\"ALLERGIES\",\"resource\":"
                    + "{\"resourceType\":\"AllergyIntolerance\",\"code\":{\"text\":\"" + a + "\"}}}");
        }
        String withheld = String.join(",", c.withheld().stream().map(w -> "\"" + w + "\"").toList());
        return Reply.ok("{\"healthId\":\"" + hid + "\",\"records\":[" + records + "],\"withheld\":[" + withheld
                + "],\"quarantined\":[]}");
    });

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ServiceProps.register(r, "prescription_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false");
        r.add("medigraph.prescription.records-url", RECORDS::url);
    }

    @AfterAll
    static void stop() {
        RECORDS.close();
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
        RECORDS_WRITES_DOWN.set(false);
    }

    private Resp prescribe(String token, String drug, String override) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("drug", drug, "dosage", "as directed"));
        if (override != null) {
            body.put("overrideReason", override);
        }
        return api.post("/api/v1/patients/" + hid + "/prescriptions", body, token);
    }

    @Test
    void cleanPrescriptionIsRecordedAsTheDoctorWithAnIdempotencyKey() {
        CHARTS.put(hid, new Chart(List.of(), List.of(), List.of()));
        Resp r = prescribe(drA, "metformn", null); // typo, corrected by the BK-tree
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        assertThat(r.json().get("outcome").asText()).isEqualTo("APPROVED");
        assertThat(r.json().at("/resolution/kind").asText()).isEqualTo("CORRECTED");
        assertThat(r.json().at("/resolution/drug").asText()).isEqualTo("metformin");
        assertThat(r.json().get("status").asText()).isEqualTo("RECORDED");

        StubHttpServer.Request write = RECORDS.received().stream()
                .filter(q -> q.method().equals("POST") && q.path().contains(hid)).findFirst().orElseThrow();
        assertThat(write.authorization()).isEqualTo("Bearer " + drA); // token relay: consent applies to dr-a
        assertThat(write.body()).contains("\"resourceType\":\"MedicationRequest\"", "metformin");
        String id = r.json().get("prescriptionId").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM prescription WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RECORDED");
    }

    @Test
    void majorInteractionBlocksUntilAnAuditedOverrideWhoseReasonNeverReachesTheLedger() {
        CHARTS.put(hid, new Chart(List.of("Warfarin"), List.of(), List.of()));
        Resp blocked = prescribe(drA, "aspirin", null);
        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.json().get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.json().at("/alerts/0/severity").asText()).isEqualTo("MAJOR");
        assertThat(blocked.json().at("/checkedAgainst/currentMedications/0").asText()).isEqualTo("Warfarin");
        assertThat(RECORDS.received()).noneMatch(q -> q.method().equals("POST") && q.path().contains(hid));

        String reason = "Cardiology reviewed, INR monitored daily";
        Resp over = prescribe(drA, "aspirin", reason);
        assertThat(over.status()).as(over.toString()).isEqualTo(201);
        assertThat(over.json().get("outcome").asText()).isEqualTo("APPROVED_WITH_OVERRIDE");
        String overrideId = over.json().get("overrideId").asText();
        assertThat(jdbc.queryForObject("SELECT reason FROM prescription_override WHERE override_id = ?", String.class,
                overrideId)).isEqualTo(reason);
        String anchor = jdbc.queryForObject("SELECT payload FROM outbox_message WHERE destination = 'ledger' "
                + "AND aggregate_id = ?", String.class, "override:" + overrideId);
        assertThat(anchor).contains("PRESCRIPTION_OVERRIDE", "\"severity\":\"MAJOR\"").doesNotContain("Cardiology");
    }

    @Test
    void allergyIsContraindicatedAndAnIncompleteSafetyCheckIsRefused() {
        CHARTS.put(hid, new Chart(List.of(), List.of("Penicillin"), List.of()));
        Resp r = prescribe(drA, "amoxicillin", null);
        assertThat(r.json().get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(r.json().at("/alerts/0/type").asText()).isEqualTo("ALLERGY");
        assertThat(r.json().at("/alerts/0/severity").asText()).isEqualTo("CONTRAINDICATED");

        CHARTS.put(hid, new Chart(List.of(), List.of(), List.of("ALLERGIES")));
        Resp refused = prescribe(drA, "amoxicillin", null);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.json().get("detail").asText()).contains("ALLERGIES");

        assertThat(prescribe(TestTokens.token("nu-1", "HOSP-A", com.medigraph.common.security.Role.NURSE),
                "metformin", null).status()).isEqualTo(403);
        assertThat(prescribe(null, "metformin", null).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/patients/" + hid + "/prescriptions", Map.of("drug", ""), drA).status()).isEqualTo(400);
    }

    @Test
    void recordsOutageLeavesTheDecisionPendingAndIssueRetriesIdempotently() {
        CHARTS.put(hid, new Chart(List.of(), List.of(), List.of()));
        RECORDS_WRITES_DOWN.set(true);
        Resp pending = prescribe(drA, "paracetamol", null);
        assertThat(pending.status()).as(pending.toString()).isEqualTo(202);
        assertThat(pending.json().get("status").asText()).isEqualTo("PENDING_RECORD");
        String id = pending.json().get("prescriptionId").asText();

        assertThat(api.post("/api/v1/prescriptions/" + id + "/issue", null, TestTokens.doctor("dr-b", "HOSP-A")).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/prescriptions/" + id + "/issue", null, drA).status()).isEqualTo(503);

        RECORDS_WRITES_DOWN.set(false);
        Resp issued = api.post("/api/v1/prescriptions/" + id + "/issue", null, drA);
        assertThat(issued.status()).as(issued.toString()).isEqualTo(200);
        assertThat(issued.json().get("status").asText()).isEqualTo("RECORDED");
        List<String> keys = RECORDS.received().stream().filter(q -> q.method().equals("POST") && q.path().contains(hid))
                .map(q -> q.body()).distinct().toList();
        assertThat(keys).hasSize(1); // every attempt sent the identical order
        assertThat(api.post("/api/v1/prescriptions/" + id + "/issue", null, drA).json().get("recordId").asText())
                .isEqualTo(issued.json().get("recordId").asText());
        assertThat(api.post("/api/v1/prescriptions/" + UUID.randomUUID() + "/issue", null, drA).status()).isEqualTo(404);
    }

    @Test
    void drugLookupUsesTrieAndBkTree() {
        JsonNode s = api.get("/api/v1/drugs?prefix=am", drA).json();
        assertThat(s.toString()).contains("amoxicillin", "amlodipine");
        assertThat(api.get("/api/v1/drugs/resolution?name=warfrin", drA).json().get("drug").asText()).isEqualTo("warfarin");
        assertThat(api.get("/api/v1/drugs?prefix=am", TestTokens.patient(hid)).status()).isEqualTo(403);
    }
}
