package com.medigraph.service.beds;

import com.medigraph.common.id.HealthId;
import com.medigraph.common.security.Role;
import com.medigraph.service.beds.application.BedAllocation;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** beds-service over real PostgreSQL (embedded): CAS on bed_inventory.version, JDBC holds, bounding-box locator. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BedsServiceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ServiceProps.register(r, "beds_test");
        r.add("medigraph.outbox.relay-enabled", () -> "false");
        r.add("medigraph.beds.expiry-interval", () -> "0s");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BedAllocation allocation;

    private Api api;
    private String tag;
    private final String admin = TestTokens.admin();
    private final String dr = TestTokens.doctor("dr-bed", "HOSP-B");

    @BeforeEach
    void setUp() {
        api = Api.local(port);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private String hospital(String suffix, double lat, double lon, int beds, int occupied) {
        String id = "H-" + tag + "-" + suffix;
        Resp r = api.send("PUT", "/api/v1/hospitals/" + id, Map.of("name", suffix, "lat", lat, "lon", lon,
                "icuBeds", beds, "occupied", occupied), admin, Map.of());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("shard").asText()).startsWith("beds-shard-");
        return id;
    }

    private Resp hold(String hospitalId, String token) {
        return api.post("/api/v1/beds/holds", Map.of("hospitalId", hospitalId,
                "patientId", HealthId.generate().digits()), token);
    }

    @Test
    void manyClientsRaceForTheLastIcuBedOverHttpExactlyOneWins() throws Exception {
        String h = hospital("race", 28.61, 77.21, 10, 9);
        int clients = 24;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return hold(h, dr).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : fs) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(clients - 1);
        Resp inv = api.get("/api/v1/hospitals/" + h + "/beds", dr);
        assertThat(inv.json().get("held").asInt()).isEqualTo(1);
        assertThat(inv.json().get("free").asInt()).isZero();
    }

    @Test
    void nearestHoldReleaseConfirm() {
        String near = hospital("near", 12.9716, 77.5946, 2, 0);
        String far = hospital("far", 13.0827, 77.5877, 1, 0);
        String full = hospital("full", 12.9720, 77.5950, 1, 1);
        hospital("other-city", 19.0760, 72.8777, 5, 0); // Mumbai: outside the radius and the bounding box

        Resp nearest = api.get("/api/v1/beds/nearest?lat=12.97&lon=77.59&k=2&maxKm=30", dr);
        assertThat(nearest.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        nearest.json().forEach(n -> ids.add(n.get("hospitalId").asText()));
        assertThat(ids).startsWith(near).doesNotContain(full);
        assertThat(nearest.json().get(0).get("distanceKm").asDouble()).isLessThan(1.0);

        String h1 = hold(far, dr).json().get("holdId").asText();
        assertThat(hold(far, dr).status()).isEqualTo(409);
        assertThat(api.delete("/api/v1/beds/holds/" + h1, dr).json().get("released").asBoolean()).isTrue();
        assertThat(api.delete("/api/v1/beds/holds/" + h1, dr).json().get("released").asBoolean()).isFalse();
        String h2 = hold(far, dr).json().get("holdId").asText();
        Resp confirmed = api.post("/api/v1/beds/holds/" + h2 + "/confirm", null, dr);
        assertThat(confirmed.json().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(api.post("/api/v1/beds/holds/" + h2 + "/confirm", null, dr).status()).isEqualTo(409);
        Resp inv = api.get("/api/v1/hospitals/" + far + "/beds", dr);
        assertThat(inv.json().get("occupied").asInt()).isEqualTo(1);
        assertThat(inv.json().get("held").asInt()).isZero();
        assertThat(api.post("/api/v1/beds/holds/nope/confirm", null, dr).status()).isEqualTo(404);
    }

    @Test
    void expiredHoldReturnsTheBedAndCannotBeConfirmed() {
        String h = hospital("ttl", 22.57, 88.36, 1, 0);
        String holdId = hold(h, dr).json().get("holdId").asText();
        jdbc.update("UPDATE bed_hold SET expires_at = now() - interval '1 minute' WHERE hold_id = ?", holdId);
        assertThat(allocation.expireHolds()).isGreaterThanOrEqualTo(1);
        assertThat(allocation.expireHolds()).isZero(); // idempotent
        assertThat(api.get("/api/v1/hospitals/" + h + "/beds", dr).json().get("free").asInt()).isEqualTo(1);
        assertThat(api.post("/api/v1/beds/holds/" + holdId + "/confirm", null, dr).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE aggregate_id = ? "
                + "AND event_type = 'BedHeld'", Integer.class, holdId)).isEqualTo(1);
    }

    @Test
    void capacityCannotDropBelowOccupiedPlusHeld() {
        String h = hospital("cap", 26.85, 80.95, 2, 1);
        hold(h, dr);
        Resp shrink = api.send("PUT", "/api/v1/hospitals/" + h, Map.of("name", "cap", "lat", 26.85, "lon", 80.95,
                "icuBeds", 1, "occupied", 1), admin, Map.of());
        assertThat(shrink.status()).isEqualTo(409);
        Resp grow = api.send("PUT", "/api/v1/hospitals/" + h, Map.of("name", "cap", "lat", 26.85, "lon", 80.95,
                "icuBeds", 4, "occupied", 1), admin, Map.of());
        assertThat(grow.status()).isEqualTo(200);
        assertThat(api.get("/api/v1/hospitals/" + h + "/beds", dr).json().get("free").asInt()).isEqualTo(2);
    }

    @Test
    void rolesAndValidation() {
        String h = hospital("auth", 18.52, 73.85, 3, 0);
        assertThat(hold(h, null).status()).isEqualTo(401);
        assertThat(hold(h, TestTokens.patient(HealthId.generate().digits())).status()).isEqualTo(403);
        assertThat(hold(h, TestTokens.token("rc", "HOSP-B", Role.RECEPTION)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/beds/nearest?lat=18.5&lon=73.8", TestTokens.token("rc", "HOSP-B", Role.RECEPTION))
                .status()).isEqualTo(200);
        assertThat(api.send("PUT", "/api/v1/hospitals/X-" + tag, Map.of("name", "x", "lat", 1, "lon", 1, "icuBeds", 1,
                "occupied", 0), dr, Map.of()).status()).isEqualTo(403);
        assertThat(api.send("PUT", "/api/v1/hospitals/X-" + tag, Map.of("name", "x", "lat", 91, "lon", 1,
                "icuBeds", 1, "occupied", 0), admin, Map.of()).status()).isEqualTo(400);
        assertThat(api.send("PUT", "/api/v1/hospitals/X-" + tag, Map.of("name", "x", "lat", 1, "lon", 1, "icuBeds", 1,
                "occupied", 2), admin, Map.of()).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/beds/holds", Map.of("hospitalId", h, "patientId", "12345678901234"), dr).status())
                .isEqualTo(400); // Verhoeff check digit
        assertThat(api.get("/api/v1/beds/nearest?lat=18.5&lon=73.8&k=500", dr).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/hospitals/missing-" + tag + "/beds", dr).status()).isEqualTo(404);
    }
}
