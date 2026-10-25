package com.medigraph.service.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.prescription.OverrideAuditLog;
import com.medigraph.prescription.OverrideRecord;
import com.medigraph.prescription.SafetyAlert;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

/** Append-only (no UPDATE/DELETE paths). Joins the caller's transaction. */
public final class JdbcOverrideAuditLog implements OverrideAuditLog {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOverrideAuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(OverrideRecord r) {
        try {
            jdbc.update("INSERT INTO prescription_override (override_id, doctor_id, patient_id, drug, alerts, reason, at) "
                            + "VALUES (?,?,?,?,?::jsonb,?,?)", r.overrideId(), r.doctorId(), r.patientId(), r.drug(),
                    json.writeValueAsString(r.overriddenAlerts()), r.reason(), Timestamp.from(r.at()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<OverrideRecord> forPatient(String patientId) {
        return jdbc.query("SELECT * FROM prescription_override WHERE patient_id = ? ORDER BY at", (rs, n) -> {
            try {
                return new OverrideRecord(rs.getString("override_id"), rs.getString("doctor_id"),
                        rs.getString("patient_id"), rs.getString("drug"),
                        json.readValue(rs.getString("alerts"), new TypeReference<List<SafetyAlert>>() { }),
                        rs.getString("reason"), rs.getTimestamp("at").toInstant());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }, patientId);
    }
}
