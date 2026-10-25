package com.medigraph.service.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.security.Role;
import com.medigraph.testsupport.Api;
import com.medigraph.testsupport.Api.Resp;
import com.medigraph.testsupport.ServiceProps;
import com.medigraph.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityServiceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ServiceProps.register(r, "identity_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false"); // assert on the rows themselves
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private final String receptionA = TestTokens.token("rc-a", "HOSP-A", Role.RECEPTION);
    private final String doctorB = TestTokens.doctor("dr-b", "HOSP-B");

    @BeforeEach
    void setUp() {
        api = Api.local(port);
    }

    private static Map<String, Object> person(String mrn, String given, String family, String dob, String phone, String city) {
        return Map.of("localMrn", mrn, "givenName", given, "familyName", family, "birthDate", dob, "gender", "female",
                "phone", phone, "city", city);
    }

    @Test
    void secondFacilityRegistrationAutoLinksToTheSameHealthId() {
        Resp a = api.post("/api/v1/patients", person("MRN-1001", "Asha", "Verma", "1990-04-12", "9810000001", "Delhi"), receptionA);
        assertThat(a.status()).as(a.toString()).isEqualTo(201);
        assertThat(a.json().get("decision").asText()).isEqualTo("NEW");
        // nobody to compare against yet: no weight at all, rather than "-Infinity" leaking into the JSON
        assertThat(a.json().path("matchWeight").isMissingNode() || a.json().get("matchWeight").isNull()).isTrue();
        String hid = a.json().get("healthId").asText();
        assertThat(a.json().get("formattedHealthId").asText()).matches("\\d{4}-\\d{4}-\\d{4}-\\d{2}");

        Resp b = api.post("/api/v1/patients", person("B-77", "Aasha", "Varma", "1990-04-12", "+91 98100 00001", "New Delhi"), doctorB);
        assertThat(b.json().get("decision").asText()).isEqualTo("AUTO_LINK");
        assertThat(b.json().get("matchWeight").isNumber()).isTrue();
        assertThat(b.json().get("healthId").asText()).isEqualTo(hid);

        // the patient sees their own identity; another patient, a doctor and an anonymous caller do not
        assertThat(api.get("/api/v1/patients/" + hid, TestTokens.patient(hid)).json().get("registrations")).hasSize(2);
        String otherHid = api.post("/api/v1/patients", person("MRN-2", "Ravi", "Kumar", "1980-01-01", "9000000002", "Agra"),
                receptionA).json().get("healthId").asText();
        assertThat(api.get("/api/v1/patients/" + hid, TestTokens.patient(otherHid)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/patients/" + hid, doctorB).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/patients/" + hid, null).status()).isEqualTo(401);

        // events: identifiers only, never demographics
        String payloads = String.join("\n", jdbc.queryForList(
                "SELECT payload FROM outbox_message WHERE aggregate_id = ?", String.class, hid));
        assertThat(payloads).contains(hid).doesNotContain("Asha", "Verma", "9810000001");
    }

    @Test
    void ambiguousMatchGoesToStewardWhoMergesTheTwoHealthIds() {
        JsonNode first = api.post("/api/v1/patients", person("M-1", "Kavya", "Raghunathan", "1993-02-20", "9000044444", "Chennai"),
                receptionA).json();
        JsonNode second = api.post("/api/v1/patients", person("M-9", "Kavya", "Raghunathan", "1993-02-20", "9000055555", "Pune"),
                doctorB).json();
        assertThat(second.get("decision").asText()).isEqualTo("REVIEW");
        String hid1 = first.get("healthId").asText();
        String hid2 = second.get("healthId").asText();
        assertThat(hid2).isNotEqualTo(hid1);

        assertThat(api.get("/api/v1/mpi/reviews", doctorB).status()).isEqualTo(403);
        String taskId = null;
        for (JsonNode t : api.get("/api/v1/mpi/reviews", TestTokens.steward()).json()) {
            if (t.get("recordId").asText().equals("HOSP-B:M-9")) {
                taskId = t.get("id").asText();
            }
        }
        assertThat(taskId).isNotNull();
        Resp merged = api.post("/api/v1/mpi/reviews/" + taskId + "/resolution", Map.of("merge", true), TestTokens.steward());
        assertThat(merged.json().get("healthId").asText()).isEqualTo(hid1);

        // the provisional ID resolves to the survivor; the patient can still sign in with the old one
        Resp viaOld = api.get("/api/v1/patients/" + hid2, TestTokens.patient(hid2));
        assertThat(viaOld.json().get("healthId").asText()).isEqualTo(hid1);
        assertThat(viaOld.json().get("aliases")).hasSize(2);
        Resp internal = api.get("/internal/patients/" + hid2, TestTokens.service("records-service"));
        assertThat(internal.json().get("healthId").asText()).isEqualTo(hid1);
        assertThat(api.get("/internal/patients/" + hid2, doctorB).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE event_type = 'PatientMerged' "
                + "AND aggregate_id = ?", Integer.class, hid1)).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReplaysTheFirstResponseInsteadOfRegisteringTwice() {
        Map<String, Object> body = person("IDEM-1", "Neha", "Joshi", "1985-07-07", "9000077777", "Pune");
        Map<String, String> key = Map.of("Idempotency-Key", "register-IDEM-1-attempt");
        Resp first = api.post("/api/v1/patients", body, receptionA, key);
        Resp retry = api.post("/api/v1/patients", body, receptionA, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.json()).isEqualTo(first.json());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_registration WHERE local_mrn = 'IDEM-1'", Integer.class))
                .isEqualTo(1);

        Resp reused = api.post("/api/v1/patients", person("IDEM-2", "X", "Y", "1985-07-07", "1", "Z"), receptionA, key);
        assertThat(reused.status()).isEqualTo(422);
        // without a key the duplicate MRN hits the unique constraint: 409, not a second identity
        Resp dup = api.post("/api/v1/patients", body, receptionA);
        assertThat(dup.status()).as(dup.toString()).isEqualTo(409);
    }

    @Test
    void erasureScrubsDemographicsAndRejectsBadInput() {
        String hid = api.post("/api/v1/patients", person("E-1", "Farhan", "Ali", "1975-03-03", "9000088888", "Lucknow"),
                receptionA).json().get("healthId").asText();
        Resp erased = api.post("/internal/patients/" + hid + "/erasure", null, TestTokens.service("records-service"));
        assertThat(erased.json().get("registrationsScrubbed").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT given_name FROM patient_registration WHERE local_mrn = 'E-1'", String.class)).isNull();

        String typo = hid.substring(0, 13) + (char) ('0' + (hid.charAt(13) - '0' + 1) % 10); // Verhoeff catches single-digit typos
        Resp badId = api.get("/api/v1/patients/" + typo, TestTokens.steward());
        assertThat(badId.status()).as(badId.toString()).isEqualTo(400);
        Resp invalid = api.post("/api/v1/patients", Map.of("localMrn", "bad mrn!"), receptionA);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.header("Content-Type")).contains("application/problem+json");
        assertThat(api.post("/api/v1/patients", person("P-1", "A", "B", "1990-01-01", "1", "C"),
                TestTokens.patient(hid)).status()).isEqualTo(403);
    }
}
