package com.medigraph.service.beds.adapter.out.persistence;

import com.medigraph.beds.BedHold;
import com.medigraph.beds.HoldStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;


public class JdbcHoldStore implements HoldStore {

    private final JdbcTemplate jdbc;

    public JdbcHoldStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<BedHold> mapper = (rs, n) -> new BedHold(rs.getString("hold_id"), rs.getString("hospital_id"),
            rs.getString("patient_id"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("expires_at")),
            BedHold.Status.valueOf(rs.getString("status")));

    @Override
    public void save(BedHold h) {
        jdbc.update("INSERT INTO bed_hold (hold_id, hospital_id, patient_id, created_at, expires_at, status) "
                        + "VALUES (?,?,?,?,?,?)", h.holdId(), h.hospitalId(), h.patientId(), ts(h.createdAt()),
                ts(h.expiresAt()), h.status().name());
    }

    @Override
    public Optional<BedHold> find(String holdId) {
        return jdbc.query("SELECT * FROM bed_hold WHERE hold_id = ?", mapper, holdId).stream().findFirst();
    }

    @Override
    public boolean transition(String holdId, BedHold.Status from, BedHold.Status to) {
        return jdbc.update("UPDATE bed_hold SET status = ? WHERE hold_id = ? AND status = ?", to.name(), holdId,
                from.name()) == 1;
    }

    @Override
    public List<BedHold> activeExpiredAt(Instant now) {
        return jdbc.query("SELECT * FROM bed_hold WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at LIMIT 500",
                mapper, ts(now));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t.toInstant();
    }
}
