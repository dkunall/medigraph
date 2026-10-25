package com.medigraph.service.beds.adapter.out.persistence;

import com.medigraph.beds.BedInventory;
import com.medigraph.beds.BedRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** Optimistic locking: the UPDATE only matches when the version read earlier is still current. */
public class JdbcBedRepository implements BedRepository {

    private final JdbcTemplate jdbc;

    public JdbcBedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BedInventory> find(String hospitalId) {
        return jdbc.query("SELECT * FROM bed_inventory WHERE hospital_id = ?", (rs, n) -> new BedInventory(
                rs.getString("hospital_id"), rs.getInt("total"), rs.getInt("occupied"), rs.getInt("held"),
                rs.getLong("version")), hospitalId).stream().findFirst();
    }

    @Override
    public boolean compareAndSet(BedInventory expected, BedInventory updated) {
        return jdbc.update("UPDATE bed_inventory SET total = ?, occupied = ?, held = ?, version = ? "
                        + "WHERE hospital_id = ? AND version = ?", updated.total(), updated.occupied(), updated.held(),
                updated.version(), expected.hospitalId(), expected.version()) == 1;
    }

    @Override
    public void upsert(BedInventory i) {
        jdbc.update("INSERT INTO bed_inventory (hospital_id, total, occupied, held, version) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT (hospital_id) DO UPDATE SET total = EXCLUDED.total, occupied = EXCLUDED.occupied, "
                        + "held = EXCLUDED.held, version = bed_inventory.version + 1",
                i.hospitalId(), i.total(), i.occupied(), i.held(), i.version());
    }
}
