package com.medigraph.service.gateway;

import com.medigraph.testsupport.Api;
import com.medigraph.testsupport.Api.Resp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitTest {

    static final RecordingBackend ICU = new RecordingBackend("icu");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("medigraph.gateway.dev-issuer.enabled", () -> "true");
        r.add("medigraph.gateway.services.icu", ICU::url);
        r.add("medigraph.gateway.rate-limit.capacity", () -> "3");
        r.add("medigraph.gateway.rate-limit.refill-per-second", () -> "0.01");
    }

    @AfterAll
    static void stop() {
        ICU.close();
    }

    @LocalServerPort
    int port;

    @Test
    void burstIsAllowedThenRfc7807TooManyRequestsPerSubject() {
        Api api = Api.local(port);
        // token endpoint is limited per peer address: mint both tokens within its own 3-request budget
        String a = api.post("/dev/token", Map.of("subject", "nurse-a", "roles", List.of("NURSE")), null)
                .json().get("access_token").asText();
        String b = api.post("/dev/token", Map.of("subject", "nurse-b", "roles", List.of("NURSE")), null)
                .json().get("access_token").asText();
        for (int i = 0; i < 3; i++) {
            assertThat(api.get("/api/v1/icu/patients/1/alerts", a).status()).isEqualTo(200);
        }
        Resp limited = api.get("/api/v1/icu/patients/1/alerts", a);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        assertThat(limited.raw().headers().firstValue("Content-Type").orElse("")).contains("problem+json");
        assertThat(limited.json().get("status").asInt()).isEqualTo(429);
        assertThat(ICU.seen).hasSize(3); // the 4th never reached the backend
        assertThat(api.get("/api/v1/icu/patients/1/alerts", b).status()).isEqualTo(200); // other subject unaffected
        assertThat(api.get("/actuator/health/liveness", null).status()).isEqualTo(200); // probes never limited
    }
}
