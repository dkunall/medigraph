package com.medigraph.service.beds.adapter.out.postgis;

import com.medigraph.beds.NearbyHospital;
import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.service.beds.adapter.out.persistence.JdbcHaversineLocator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Real PostGIS (Testcontainers): applies the service migrations plus the PostGIS migration, then checks the
 * GiST KNN locator against the portable bounding-box + haversine locator on the same rows. Skipped automatically
 * when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgisHospitalLocatorContainerTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/postgis").load().migrate();
        jdbc = new JdbcTemplate(ds);
        Object[][] rows = {
                {"BLR-1", 12.9716, 77.5946, 4, 0}, {"BLR-2", 12.9352, 77.6245, 2, 1}, {"BLR-FULL", 12.9750, 77.6000, 1, 1},
                {"BLR-3", 13.0827, 77.5877, 3, 0}, {"MYS-1", 12.2958, 76.6394, 5, 0}, {"MUM-1", 19.0760, 72.8777, 9, 0}};
        for (Object[] r : rows) {
            jdbc.update("INSERT INTO hospital(id, name, lat, lon) VALUES (?, ?, ?, ?)", r[0], r[0], r[1], r[2]);
            jdbc.update("INSERT INTO bed_inventory(hospital_id, total, occupied, held, version) VALUES (?, ?, ?, 0, 0)",
                    r[0], r[3], r[4]);
        }
    }

    @Test
    void knnMatchesTheHaversineLocator() {
        GeoPoint blr = new GeoPoint(12.97, 77.59);
        List<NearbyHospital> postgis = new PostgisHospitalLocator(jdbc).nearestWithFreeIcu(blr, 3, 200);
        List<NearbyHospital> haversine = new JdbcHaversineLocator(jdbc).nearestWithFreeIcu(blr, 3, 200);
        assertThat(postgis).extracting(n -> n.hospital().id()).containsExactly("BLR-1", "BLR-2", "BLR-3");
        assertThat(postgis).extracting(n -> n.hospital().id())
                .containsExactlyElementsOf(haversine.stream().map(n -> n.hospital().id()).toList());
        for (int i = 0; i < postgis.size(); i++) {
            // spheroid (PostGIS) vs sphere (haversine): agree within 0.5 %
            assertThat(postgis.get(i).distanceKm()).isCloseTo(haversine.get(i).distanceKm(),
                    within(haversine.get(i).distanceKm() * 0.005 + 0.01));
        }
    }

    @Test
    void radiusExcludesFarAndFullHospitals() {
        List<NearbyHospital> r = new PostgisHospitalLocator(jdbc).nearestWithFreeIcu(new GeoPoint(12.97, 77.59), 10, 50);
        assertThat(r).extracting(n -> n.hospital().id()).doesNotContain("BLR-FULL", "MYS-1", "MUM-1");
    }
}
