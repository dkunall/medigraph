package com.medigraph.service.icu;

import com.medigraph.common.id.HealthId;
import com.medigraph.common.security.Role;
import com.medigraph.icu.News2Score;
import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalType;
import com.medigraph.icu.stream.WindowScore;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.service.icu.application.AlertRecorder;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** icu-service with the in-process stream processor and a stubbed identity service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IcuServiceTest {

    static final long MIN = 60_000;
    static final Map<String, String> CANONICAL = new ConcurrentHashMap<>();

    static final StubHttpServer IDENTITY = new StubHttpServer().on("/internal/patients/", req -> {
        String id = req.path().substring("/internal/patients/".length());
        String c = CANONICAL.get(id);
        return c == null ? new Reply(404, "{\"detail\":\"unknown\"}")
                : Reply.ok("{\"healthId\":\"" + c + "\",\"aliases\":[]}");
    });

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ServiceProps.register(r, "icu_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false");
        r.add("medigraph.icu.identity-url", IDENTITY::url);
        r.add("medigraph.icu.processor", () -> "in-memory");
        r.add("medigraph.icu.watermark-interval", () -> "0s");
    }

    @TestConfiguration
    static class Tokens {
        @Bean
        ServiceTokenProvider serviceTokenProvider() {
            return () -> TestTokens.service("icu-service");
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
    @Autowired
    AlertRecorder recorder;

    private Api api;
    private String hid;
    private long t0;
    private final String nurse = TestTokens.token("rn-1", "HOSP-A", Role.NURSE);

    @BeforeEach
    void setUp() {
        api = Api.local(port);
        hid = HealthId.generate().digits();
        CANONICAL.put(hid, hid);
        long now = System.currentTimeMillis();
        t0 = now - now % (5 * MIN) - 60 * MIN; // window-aligned, an hour ago
    }

    private static List<Map<String, Object>> set(long t, int rr, int spo2, int o2, int sbp, int hr, double temp) {
        List<Map<String, Object>> l = new ArrayList<>();
        Object[][] v = {{VitalType.RESPIRATION_RATE, rr}, {VitalType.SPO2, spo2}, {VitalType.SUPPLEMENTAL_O2, o2},
                {VitalType.SYSTOLIC_BP, sbp}, {VitalType.PULSE, hr}, {VitalType.CONSCIOUSNESS, 0},
                {VitalType.TEMPERATURE, temp}};
        for (Object[] p : v) {
            l.add(Map.of("readingId", "r-" + p[0] + "-" + t, "type", p[0].toString(), "value", p[1],
                    "eventTimeMillis", t));
        }
        return l;
    }

    private Resp vitals(String patient, List<Map<String, Object>> readings, String token) {
        return api.post("/api/v1/icu/patients/" + patient + "/vitals", Map.of("readings", readings), token);
    }

    @Test
    void deterioratingPatientRaisesOneAlertPersistedWithOutboxEventUnderTheCanonicalId() {
        String alias = HealthId.generate().digits();
        CANONICAL.put(alias, hid); // merged registration: alias monitors feed the canonical patient's windows
        assertThat(vitals(alias, set(t0 + MIN, 16, 98, 0, 125, 72, 36.8), nurse).status()).isEqualTo(202);
        Resp r = vitals(hid, set(t0 + 6 * MIN, 26, 91, 1, 95, 125, 39.4),
                TestTokens.service("bedside-gateway"));
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.json().get("accepted").asInt()).isEqualTo(7);

        assertThat(api.post("/api/v1/icu/watermark?nowMillis=" + (t0 + 15 * MIN), null, TestTokens.admin()).status())
                .isEqualTo(200);
        assertThat(api.post("/api/v1/icu/watermark?nowMillis=" + (t0 + 15 * MIN), null, TestTokens.admin()).status())
                .isEqualTo(200); // idempotent

        Resp alerts = api.get("/api/v1/icu/patients/" + alias + "/alerts", TestTokens.doctor("dr-a", "HOSP-A"));
        assertThat(alerts.status()).isEqualTo(200);
        assertThat(alerts.json()).hasSize(1);
        assertThat(alerts.json().get(0).get("patientId").asText()).isEqualTo(hid);
        assertThat(alerts.json().get(0).get("risk").asText()).isEqualTo("HIGH");
        assertThat(alerts.json().get(0).get("score").asInt()).isGreaterThanOrEqualTo(7);

        List<String> events = jdbc.queryForList("SELECT payload::text FROM outbox_message WHERE event_type = "
                + "'News2AlertRaised' AND aggregate_id LIKE ?", String.class, hid + "@%");
        assertThat(events).singleElement().satisfies(p -> {
            assertThat(p).contains(hid).contains("\"risk\":\"HIGH\"");
            assertThat(p).doesNotContain("125").doesNotContain("39.4"); // scores only, no raw vitals
        });
    }

    @Test
    void redeliveredWindowIsStoredAndPublishedOnce() {
        Map<VitalType, Integer> comps = new EnumMap<>(VitalType.class);
        comps.put(VitalType.PULSE, 3);
        WindowScore w = new WindowScore(hid, t0, t0 + 5 * MIN, new News2Score(7, comps, RiskLevel.HIGH), Set.of(),
                true, "ESCALATED_TO_HIGH");
        recorder.onWindow(w);
        recorder.onWindow(w); // at-least-once redelivery from the scores topic
        assertThat(jdbc.queryForObject("SELECT count(*) FROM icu_alert WHERE patient_id = ?", Integer.class, hid))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE aggregate_id LIKE ?", Integer.class,
                hid + "@%")).isEqualTo(1);
        recorder.onWindow(new WindowScore(hid, t0 + 5 * MIN, t0 + 10 * MIN, null, Set.of(VitalType.SPO2), false, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM icu_alert WHERE patient_id = ?", Integer.class, hid))
                .isEqualTo(1);
    }

    @Test
    void healthyPatientProducesNoAlert() {
        vitals(hid, set(t0 + MIN, 16, 98, 0, 125, 72, 36.8), nurse);
        vitals(hid, set(t0 + 6 * MIN, 16, 97, 0, 122, 70, 36.9), nurse);
        api.post("/api/v1/icu/watermark?nowMillis=" + (t0 + 20 * MIN), null, TestTokens.admin());
        assertThat(api.get("/api/v1/icu/patients/" + hid + "/alerts", nurse).json()).isEmpty();
    }

    @Test
    void rolesAndValidation() {
        List<Map<String, Object>> ok = set(t0 + MIN, 16, 98, 0, 125, 72, 36.8);
        assertThat(vitals(hid, ok, null).status()).isEqualTo(401);
        assertThat(vitals(hid, ok, TestTokens.patient(hid)).status()).isEqualTo(403);
        assertThat(vitals(hid, ok, TestTokens.token("rc-1", "HOSP-A", Role.RECEPTION)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/icu/patients/" + hid + "/alerts", TestTokens.patient(hid)).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/icu/watermark?nowMillis=1", null, nurse).status()).isEqualTo(403);

        long future = System.currentTimeMillis() + 60 * MIN;
        Resp skew = vitals(hid, set(future, 16, 98, 0, 125, 72, 36.8), nurse);
        assertThat(skew.status()).isEqualTo(400);
        assertThat(skew.raw().body()).contains("future");
        assertThat(vitals(hid, List.of(), nurse).status()).isEqualTo(400);
        assertThat(vitals(hid, List.of(Map.of("readingId", "x1", "type", "BLOOD_SUGAR", "value", 5,
                "eventTimeMillis", t0)), nurse).status()).isEqualTo(400);
        assertThat(vitals(HealthId.generate().digits(), ok, nurse).status()).isEqualTo(404);
    }

    @Test
    void identityRequestsCarryTheServiceToken() {
        vitals(hid, set(t0 + MIN, 16, 98, 0, 125, 72, 36.8), nurse);
        assertThat(IDENTITY.received()).anySatisfy(req -> {
            assertThat(req.path()).endsWith(hid);
            assertThat(req.authorization()).startsWith("Bearer ");
        });
    }
}
