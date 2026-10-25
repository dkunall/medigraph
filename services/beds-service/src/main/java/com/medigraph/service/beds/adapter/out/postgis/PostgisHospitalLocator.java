package com.medigraph.service.beds.adapter.out.postgis;

import com.medigraph.beds.Hospital;
import com.medigraph.beds.HospitalLocator;
import com.medigraph.beds.NearbyHospital;
import com.medigraph.beds.geo.GeoPoint;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * PostGIS adapter: KNN with the GiST index ({@code ORDER BY geog <-> point}) and an {@code ST_DWithin} radius filter,
 * joined to bed_inventory so only hospitals with a free ICU bed are returned. Requires migration
 * {@code db/postgis/V100__postgis_location.sql} (add {@code classpath:db/postgis} to the Flyway locations).
 * <p>Exercised by {@code PostgisHospitalLocatorContainerTest} (Testcontainers, skipped when Docker is absent).
 */
public class PostgisHospitalLocator implements HospitalLocator {

    private static final String SQL = """
            SELECT h.id, h.name, h.lat, h.lon,
                   ST_Distance(h.geog, ST_MakePoint(?, ?)::geography) / 1000.0 AS km,
                   b.total - b.occupied - b.held AS free
            FROM hospital h JOIN bed_inventory b ON b.hospital_id = h.id
            WHERE b.total - b.occupied - b.held > 0
              AND ST_DWithin(h.geog, ST_MakePoint(?, ?)::geography, ?)
            ORDER BY h.geog <-> ST_MakePoint(?, ?)::geography
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public PostgisHospitalLocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NearbyHospital> nearestWithFreeIcu(GeoPoint from, int k, double maxKm) {
        double lon = from.lon();
        double lat = from.lat();
        return jdbc.query(SQL, (rs, i) -> new NearbyHospital(
                        new Hospital(rs.getString("id"), rs.getString("name"),
                                new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"))),
                        rs.getDouble("km"), rs.getInt("free")),
                lon, lat, lon, lat, maxKm * 1000.0, lon, lat, k);
    }
}
