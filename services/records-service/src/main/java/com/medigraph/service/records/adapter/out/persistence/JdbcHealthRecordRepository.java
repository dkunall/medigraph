package com.medigraph.service.records.adapter.out.persistence;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.crypto.EncryptedPayload;
import com.medigraph.service.records.application.port.out.HealthRecordRepository;
import com.medigraph.service.records.domain.HealthRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcHealthRecordRepository implements HealthRecordRepository {

    private static final RowMapper<HealthRecord> MAPPER = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        return new HealthRecord(rs.getObject("id", UUID.class), rs.getString("patient_id"), rs.getString("resource_type"),
                DataCategory.valueOf(rs.getString("category")),
                new EncryptedPayload(rs.getInt("key_version"), rs.getBytes("iv"), rs.getBytes("ciphertext")),
                rs.getString("content_hash"), rs.getString("ledger_entry_id"), rs.getString("facility"),
                rs.getString("created_by"), created == null ? null : created.toInstant());
    };

    private final JdbcTemplate jdbc;

    public JdbcHealthRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(HealthRecord r) {
        jdbc.update("INSERT INTO health_record (id, patient_id, resource_type, category, key_version, iv, ciphertext, "
                        + "content_hash, ledger_entry_id, facility, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.patientId(), r.resourceType(), r.category().name(), r.payload().keyVersion(), r.payload().iv(),
                r.payload().ciphertext(), r.contentHash(), r.ledgerEntryId(), r.facility(), r.createdBy(),
                Timestamp.from(r.createdAt()));
    }

    @Override
    public Optional<HealthRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM health_record WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<HealthRecord> forPatients(Collection<String> healthIds, DataCategory category) {
        return jdbc.query("SELECT * FROM health_record WHERE patient_id = ANY(?) AND category = ? ORDER BY created_at, id",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("varchar", healthIds.toArray()));
                    ps.setString(2, category.name());
                }, MAPPER);
    }
}
