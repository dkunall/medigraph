package com.medigraph.service.beds.adapter.out.persistence;

import com.medigraph.beds.Hospital;
import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.service.beds.application.port.out.HospitalDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

public final class JdbcHospitalDirectory implements HospitalDirectory {

    private final JdbcTemplate jdbc;

    public JdbcHospitalDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Hospital h) {
        jdbc.update("INSERT INTO hospital (id, name, lat, lon) VALUES (?,?,?,?) ON CONFLICT (id) DO UPDATE "
                + "SET name = EXCLUDED.name, lat = EXCLUDED.lat, lon = EXCLUDED.lon", h.id(), h.name(),
                h.location().lat(), h.location().lon());
    }

    @Override
    public Optional<Hospital> find(String id) {
        return jdbc.query("SELECT * FROM hospital WHERE id = ?", (rs, n) -> new Hospital(rs.getString("id"),
                rs.getString("name"), new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon"))), id).stream().findFirst();
    }
}
