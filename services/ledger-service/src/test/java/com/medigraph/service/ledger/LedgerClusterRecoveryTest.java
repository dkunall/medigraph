package com.medigraph.service.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.security.Role;
import com.medigraph.platform.security.dev.DevIssuer;
import com.medigraph.platform.security.dev.DevIssuerHttpServer;
import com.medigraph.platform.security.dev.DevTokenMinter;
import com.medigraph.testsupport.EmbeddedPg;
import com.medigraph.testsupport.FreePorts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three real ledger-service instances (separate Spring contexts, separate HTTP ports, separate PostgreSQL
 * schemas standing in for separate databases) running Raft over HTTP with JWT-authenticated peer calls.
 * Kills the leader, keeps writing through the new leader, restarts the old leader from its database and checks
 * it recovers term/log/blocks from disk and then catches up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerClusterRecoveryTest {

    private static final String SECRET = "dev-only-ledger-secret"; // test-only dev credential
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final Map<String, ConfigurableApplicationContext> nodes = new LinkedHashMap<>();
    private DevIssuerHttpServer issuer;
    private DevTokenMinter minter;
    private String serviceToken;

    @BeforeAll
    void startCluster() {
        minter = new DevTokenMinter("medigraph-dev-issuer", "medigraph");
        issuer = DevIssuerHttpServer.start(new DevIssuer(minter, Map.of("ledger-service", SECRET)));
        serviceToken = minter.mint("test-client", Set.of(Role.SERVICE), null, Duration.ofHours(1));
        for (String id : List.of("ledger1", "ledger2", "ledger3")) {
            ports.put(id, FreePorts.next());
        }
        ports.keySet().forEach(this::start);
    }

    @AfterAll
    void stopCluster() {
        nodes.values().forEach(ConfigurableApplicationContext::close);
        issuer.close();
    }

    private void start(String id) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=" + ports.get(id),
                "--spring.datasource.url=" + EmbeddedPg.url("ledger_" + id),
                "--spring.datasource.username=" + EmbeddedPg.username(),
                "--spring.datasource.password=" + EmbeddedPg.password(),
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.flyway.schemas=ledger_" + id,
                "--spring.flyway.default-schema=ledger_" + id,
                "--medigraph.security.jwt.jwk-set-uri=" + issuer.jwksUri(),
                "--medigraph.security.client.token-uri=" + issuer.tokenUri(),
                "--medigraph.security.client.secret=" + SECRET,
                "--medigraph.ledger.raft.node-id=" + id,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        ports.forEach((peer, port) -> {
            if (!peer.equals(id)) {
                args.add("--medigraph.ledger.raft.peers." + peer + "=http://localhost:" + port);
            }
        });
        nodes.put(id, new SpringApplicationBuilder(LedgerServiceApplication.class).run(args.toArray(String[]::new)));
    }

    // ------------------------------------------------------------------ HTTP helpers

    private HttpResponse<String> call(String id, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + ports.get(id) + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode status(String id) throws Exception {
        HttpResponse<String> r = call(id, "GET", "/internal/ledger/status", null, serviceToken);
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private String awaitLeader(long minTerm) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (String id : nodes.keySet()) {
                JsonNode s = status(id);
                if ("LEADER".equals(s.get("role").asText()) && s.get("term").asLong() >= minTerm) {
                    return id;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no leader elected");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private long height(String id) {
        try {
            return status(id).get("height").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String entries(String... ids) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("entryId", id);
            e.put("type", "RECORD_HASH");
            e.put("subject", "12345678901234");
            e.put("actor", "records-service");
            e.put("contentHash", com.medigraph.common.crypto.Sha256.hex(id));
            e.put("timestampMillis", 1_700_000_000_000L);
            e.put("metadata", Map.of("resourceType", "Observation"));
            list.add(e);
        }
        return JSON.writeValueAsString(list);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void leaderCommitsFollowersRedirectAndEveryReplicaPersistsTheBlock() throws Exception {
        String leader = awaitLeader(1);
        HttpResponse<String> r = call(leader, "POST", "/internal/ledger/entries", entries("e-1", "e-2", "e-3"), serviceToken);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("submitted").asInt()).isEqualTo(3);

        for (String id : nodes.keySet()) {
            await(id + " to apply block 0", () -> height(id) == 1);
        }

        String follower = nodes.keySet().stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
        HttpResponse<String> redirected = call(follower, "POST", "/internal/ledger/entries", entries("e-x"), serviceToken);
        assertThat(redirected.statusCode()).isEqualTo(421);
        assertThat(redirected.headers().firstValue("X-Raft-Leader")).contains(leader);
        assertThat(redirected.headers().firstValue("X-Raft-Leader-Url")).contains("http://localhost:" + ports.get(leader));
    }

    @Test
    @Order(2)
    void restartedLeaderRecoversFromItsDatabaseAndCatchesUp() throws Exception {
        String oldLeader = awaitLeader(1);
        long oldTerm = status(oldLeader).get("term").asLong();
        nodes.remove(oldLeader).close();

        String newLeader = awaitLeader(oldTerm + 1);
        assertThat(newLeader).isNotEqualTo(oldLeader);
        HttpResponse<String> r = call(newLeader, "POST", "/internal/ledger/entries", entries("e-4", "e-5"), serviceToken);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        // a retried entry (lost ack / outbox redelivery) is not sealed twice
        HttpResponse<String> dup = call(newLeader, "POST", "/internal/ledger/entries", entries("e-1"), serviceToken);
        assertThat(JSON.readTree(dup.body()).get("alreadySealed").asInt()).isEqualTo(1);
        long clusterHeight = height(newLeader);
        assertThat(clusterHeight).isEqualTo(2);

        // durable state of the stopped node, read straight from its schema
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(EmbeddedPg.url("ledger_" + oldLeader),
                EmbeddedPg.username(), EmbeddedPg.password()));
        assertThat(db.queryForObject("SELECT current_term FROM raft_hard_state", Long.class)).isEqualTo(oldTerm);
        assertThat(db.queryForObject("SELECT count(*) FROM ledger_block", Long.class)).isEqualTo(1);
        long loggedBefore = db.queryForObject("SELECT count(*) FROM raft_log", Long.class);
        assertThat(loggedBefore).isGreaterThanOrEqualTo(2); // leader no-op + block proposal

        start(oldLeader);
        JsonNode recovered = status(oldLeader);
        assertThat(recovered.get("height").asLong()).as("blocks reloaded from disk").isGreaterThanOrEqualTo(1);
        assertThat(recovered.get("term").asLong()).as("term reloaded from disk").isGreaterThanOrEqualTo(oldTerm);
        assertThat(recovered.get("chainValid").asBoolean()).isTrue();

        await(oldLeader + " to catch up", () -> height(oldLeader) == clusterHeight);
        JsonNode caughtUp = status(oldLeader);
        assertThat(caughtUp.get("role").asText()).isEqualTo("FOLLOWER");
        assertThat(caughtUp.get("chainValid").asBoolean()).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM ledger_block", Long.class)).isEqualTo(clusterHeight);
        assertThat(db.queryForObject("SELECT last_applied FROM ledger_state", Long.class))
                .isEqualTo(status(newLeader).get("lastApplied").asLong());

        // all replicas hold byte-identical chains: same tip hash
        String leaderNow = awaitLeader(1);
        HttpResponse<String> proof = call(leaderNow, "GET", "/internal/ledger/entries/e-4/proof", null, serviceToken);
        assertThat(proof.statusCode()).isEqualTo(200);
        String tipHash = JSON.readTree(proof.body()).get("blockHash").asText();
        JdbcTemplate other = new JdbcTemplate(new DriverManagerDataSource(EmbeddedPg.url("ledger_" + leaderNow),
                EmbeddedPg.username(), EmbeddedPg.password()));
        assertThat(db.queryForObject("SELECT hash FROM ledger_block WHERE height = 1", String.class)).isEqualTo(tipHash);
        assertThat(other.queryForObject("SELECT hash FROM ledger_block WHERE height = 1", String.class)).isEqualTo(tipHash);

        HttpResponse<String> verification = call(leaderNow, "GET", "/internal/ledger/entries/e-4/verification?contentHash="
                + com.medigraph.common.crypto.Sha256.hex("e-4"), null, serviceToken);
        assertThat(JSON.readTree(verification.body()).get("valid").asBoolean()).isTrue();
        HttpResponse<String> tampered = call(leaderNow, "GET", "/internal/ledger/entries/e-4/verification?contentHash="
                + com.medigraph.common.crypto.Sha256.hex("tampered"), null, serviceToken);
        assertThat(JSON.readTree(tampered.body()).get("valid").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void endpointsRequireTheRightRole() throws Exception {
        String any = nodes.keySet().iterator().next();
        String doctor = minter.mint("dr-1", Set.of(Role.DOCTOR), "AIIMS-DEL", Duration.ofMinutes(5));
        String steward = minter.mint("st-1", Set.of(Role.STEWARD), "AIIMS-DEL", Duration.ofMinutes(5));

        assertThat(call(any, "GET", "/internal/ledger/status", null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> bad = call(any, "GET", "/internal/ledger/status", null, "not-a-jwt");
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(bad.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        assertThat(call(any, "GET", "/internal/ledger/status", null, doctor).statusCode()).isEqualTo(403);
        assertThat(call(any, "POST", "/internal/raft/messages", "{}", doctor).statusCode()).isEqualTo(403);
        assertThat(call(any, "GET", "/api/v1/ledger/status", null, doctor).statusCode()).isEqualTo(403);
        assertThat(call(any, "GET", "/api/v1/ledger/status", null, steward).statusCode()).isEqualTo(200);
        assertThat(call(any, "GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200);
    }
}
