package com.medigraph.service.prescription.adapter.out.persistence;

import com.medigraph.prescription.PrescriptionDecision.Outcome;
import com.medigraph.service.prescription.application.port.out.PrescriptionRepository;
import com.medigraph.service.prescription.domain.IssueStatus;
import com.medigraph.service.prescription.domain.Prescription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPrescriptionRepository implements PrescriptionRepository {

    private static final RowMapper<Prescription> ROW = (rs, n) -> new Prescription(rs.getObject("id", UUID.class),
            rs.getString("patient_id"), rs.getString("prescriber_id"), rs.getString("facility"),
            rs.getString("drug_input"), rs.getString("drug"), rs.getString("dosage"),
            Outcome.valueOf(rs.getString("outcome")), IssueStatus.valueOf(rs.getString("status")),
            rs.getString("override_id"), rs.getString("record_id"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcPrescriptionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void insert(Prescription p) {
        jdbc.update("INSERT INTO prescription (id, patient_id, prescriber_id, facility, drug_input, drug, dosage, outcome, "
                        + "status, override_id, record_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(), p.patientId(), p.prescriberId(), p.facility(), p.drugInput(), p.drug(), p.dosage(),
                p.outcome().name(), p.status().name(), p.overrideId(), p.recordId(), Timestamp.from(p.createdAt()),
                Timestamp.from(p.createdAt()));
    }

    @Override
    public Optional<Prescription> find(UUID id) {
        return jdbc.query("SELECT * FROM prescription WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Conditional on PENDING_RECORD, so a late duplicate confirmation cannot overwrite a recorded id. */
    @Override
    public void markRecorded(UUID id, String recordId) {
        jdbc.update("UPDATE prescription SET status = 'RECORDED', record_id = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'PENDING_RECORD'", recordId, Timestamp.from(clock.instant()), id);
    }
}
