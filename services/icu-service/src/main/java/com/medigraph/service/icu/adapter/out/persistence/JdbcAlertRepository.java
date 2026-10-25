package com.medigraph.service.icu.adapter.out.persistence;

import com.medigraph.icu.RiskLevel;
import com.medigraph.service.icu.application.port.out.AlertRepository;
import com.medigraph.service.icu.domain.IcuAlert;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

public final class JdbcAlertRepository implements AlertRepository {

    private final JdbcTemplate jdbc;

    public JdbcAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean saveIfAbsent(IcuAlert a) {
        return jdbc.update("INSERT INTO icu_alert (patient_id, window_start, window_end, score, risk, reason) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (patient_id, window_end) DO NOTHING", a.patientId(),
                Timestamp.from(a.windowStart()), Timestamp.from(a.windowEnd()), a.score(), a.risk().name(),
                a.reason()) == 1;
    }

    @Override
    public List<IcuAlert> recent(String patientId, int limit) {
        return jdbc.query("SELECT * FROM icu_alert WHERE patient_id = ? ORDER BY window_end DESC LIMIT ?",
                (rs, n) -> new IcuAlert(rs.getString("patient_id"), rs.getTimestamp("window_start").toInstant(),
                        rs.getTimestamp("window_end").toInstant(), rs.getInt("score"),
                        RiskLevel.valueOf(rs.getString("risk")), rs.getString("reason")), patientId, limit);
    }
}
