package com.medigraph.service.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.medigraph.common.id.HealthId;
import com.medigraph.testsupport.Api;
import com.medigraph.testsupport.Api.Resp;
import com.medigraph.testsupport.FreePorts;
import com.medigraph.testsupport.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway end to end against recording fake backends: dev issuer, edge auth, routing, header hygiene, resilience. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTest {

    static final RecordingBackend IDENTITY = new RecordingBackend("identity");
    static final RecordingBackend CONSENT = new RecordingBackend("consent");
    static final RecordingBackend RECORDS = new RecordingBackend("records");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("medigraph.gateway.dev-issuer.enabled", () -> "true");
        r.add("medigraph.gateway.services.identity", IDENTITY::url);
        r.add("medigraph.gateway.services.consent", CONSENT::url);
        r.add("medigraph.gateway.services.records", RECORDS::url);
        r.add("medigraph.gateway.services.beds", () -> "http://127.0.0.1:" + FreePorts.next()); // nothing listens
        r.add("medigraph.http.connect-timeout", () -> "300ms");
        r.add("medigraph.http.initial-backoff", () -> "10ms");
    }

    @AfterAll
    static void stop() {
        IDENTITY.close();
        CONSENT.close();
        RECORDS.close();
    }

    @LocalServerPort
    int port;
    private Api api;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        api = Api.local(port);
        IDENTITY.seen.clear();
        CONSENT.seen.clear();
        RECORDS.seen.clear();
        RECORDS.status = 200;
    }

    private String userToken(String sub, String... roles) {
        Resp r = api.post("/dev/token", Map.of("subject", sub, "roles", List.of(roles), "facility", "HOSP-A"), null);
        assertThat(r.status()).isEqualTo(200);
        return r.json().get("access_token").asText();
    }

    private HttpResponse<String> clientCredentials(String id, String secret, String grant) throws Exception {
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/token"))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + grant)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void devIssuerServesJwksAndClientCredentials() throws Exception {
        Resp jwks = api.get("/.well-known/jwks.json", null);
        assertThat(jwks.status()).isEqualTo(200);
        JsonNode key = jwks.json().get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("d")).isFalse(); // public half only

        HttpResponse<String> ok = clientCredentials("consent-service", "dev-only-consent-secret", "client_credentials");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(Api.JSON.readTree(ok.body()).get("token_type").asText()).isEqualTo("Bearer");
        assertThat(clientCredentials("consent-service", "wrong", "client_credentials").statusCode()).isEqualTo(401);
        assertThat(clientCredentials("nobody", "dev-only-consent-secret", "client_credentials").statusCode()).isEqualTo(401);
        assertThat(clientCredentials("consent-service", "dev-only-consent-secret", "password").statusCode()).isEqualTo(400);
        assertThat(api.post("/dev/token", Map.of("subject", "x", "roles", List.of("SERVICE")), null).status()).isEqualTo(400);
    }

    @Test
    void routesByMostSpecificPatternAndRelaysTheToken() {
        String hid = HealthId.generate().digits();
        String token = userToken("dr-1", "DOCTOR");
        Resp consents = api.send("GET", "/api/v1/patients/" + hid + "/consents?active=true", null, token,
                Map.of("X-Correlation-Id", "corr-12345678", "X-Forwarded-For", "6.6.6.6", "Cookie", "s=1",
                        "X-Forwarded-Host", "evil.example"));
        assertThat(consents.status()).isEqualTo(200);
        assertThat(consents.json().get("service").asText()).isEqualTo("consent");
        RecordingBackend.Seen seen = CONSENT.seen.get(0);
        assertThat(seen.pathAndQuery()).isEqualTo("/api/v1/patients/" + hid + "/consents?active=true");
        assertThat(seen.header("Authorization")).isEqualTo("Bearer " + token);
        assertThat(seen.header("X-Correlation-Id")).isEqualTo("corr-12345678");
        assertThat(seen.header("X-Forwarded-For")).isEqualTo("127.0.0.1"); // set from the socket, not the client
        assertThat(seen.header("Cookie")).isNull();
        assertThat(seen.header("X-Forwarded-Host")).isNull();
        assertThat(consents.header("Set-Cookie")).isNull();
        assertThat(consents.header("Idempotent-Replayed")).isEqualTo("true"); // the platform's replay marker is relayed

        assertThat(api.get("/api/v1/patients/" + hid, token).json().get("service").asText()).isEqualTo("identity");
        assertThat(api.get("/api/v1/patients/" + hid + "/records", token).json().get("service").asText()).isEqualTo("records");
    }

    @Test
    void relaysBodyStatusAndIdempotencyKey() {
        String token = userToken("rc-1", "RECEPTION");
        IDENTITY.status = 201;
        try {
            Resp created = api.post("/api/v1/patients", Map.of("name", "A"), token, Map.of("Idempotency-Key", "k-123456789"));
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.header("Location")).isEqualTo("/api/v1/x/1");
            RecordingBackend.Seen seen = IDENTITY.seen.get(0);
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.body()).contains("\"name\":\"A\"");
            assertThat(seen.header("Idempotency-Key")).isEqualTo("k-123456789");
        } finally {
            IDENTITY.status = 200;
        }
    }

    @Test
    void edgeAuthentication() throws Exception {
        assertThat(api.get("/api/v1/patients", null).status()).isEqualTo(401);
        // signed by a key the gateway does not trust (correct issuer/audience/claims otherwise)
        assertThat(api.get("/api/v1/patients", TestTokens.doctor("dr", "H")).status()).isEqualTo(401);
        assertThat(api.get("/api/v1/patients", "not.a.jwt").status()).isEqualTo(401);
        assertThat(IDENTITY.seen).isEmpty();
        String serviceToken = Api.JSON.readTree(clientCredentials("records-service", "dev-only-records-secret",
                "client_credentials").body()).get("access_token").asText();
        assertThat(api.get("/internal/patients/123", serviceToken).status()).isEqualTo(403); // never exposed
        assertThat(api.get("/actuator/health/liveness", null).status()).isEqualTo(200);
    }

    @Test
    void unknownRoutesAndTraversal() {
        String token = userToken("dr-2", "DOCTOR");
        Resp r = api.get("/api/v1/unknown", token);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.raw().headers().firstValue("Content-Type").orElse("")).contains("problem+json");
        assertThat(api.get("/api/v1/icux", token).status()).isEqualTo(404);
    }

    @Test
    void backendFailuresBecome503AndUpstream5xxIsRelayed() {
        String token = userToken("dr-3", "DOCTOR");
        Resp down = api.get("/api/v1/beds/nearest?lat=1&lon=1", token);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.json().get("type").asText()).contains("dependency-unavailable");
        for (int i = 0; i < 12; i++) { // enough failures to open the breaker; still a clean 503
            assertThat(api.get("/api/v1/beds/nearest?lat=1&lon=1", token).status()).isEqualTo(503);
        }
        RECORDS.status = 500;
        assertThat(api.get("/api/v1/records/r1/verification", token).status()).isEqualTo(500);
        assertThat(RECORDS.seen).hasSize(1); // a 5xx answer is not retried (the backend may have acted)
    }
}
