package com.medigraph.service.records.adapter.out.persistence;

import com.medigraph.crypto.WrappedKey;
import com.medigraph.crypto.WrappedKeyStore;
import com.medigraph.service.records.application.port.out.KeyInventory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

/**
 * Wrapped DEKs in Postgres. {@code putIfAbsent} is race-safe via {@code ON CONFLICT DO NOTHING} + re-read (first
 * writer wins, every caller gets the stored row). {@code delete} physically removes the row and writes a tombstone in
 * the same statement batch (callers run it inside their transaction).
 */
public final class JdbcWrappedKeyStore implements WrappedKeyStore, KeyInventory {

    private static final RowMapper<WrappedKey> MAPPER = (rs, n) -> new WrappedKey(rs.getString("patient_id"),
            rs.getInt("version"), rs.getString("kek_id"), rs.getBytes("iv"), rs.getBytes("wrapped"));

    private final JdbcTemplate jdbc;

    public JdbcWrappedKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WrappedKey putIfAbsent(WrappedKey key) {
        jdbc.update("INSERT INTO patient_key (patient_id, version, kek_id, iv, wrapped) VALUES (?,?,?,?,?) "
                + "ON CONFLICT (patient_id) DO NOTHING", key.patientId(), key.version(), key.kekId(), key.iv(), key.wrapped());
        return find(key.patientId()).orElseThrow(() -> new IllegalStateException("key row vanished for " + key.patientId()));
    }

    @Override
    public Optional<WrappedKey> find(String patientId) {
        return jdbc.query("SELECT * FROM patient_key WHERE patient_id = ?", MAPPER, patientId).stream().findFirst();
    }

    @Override
    public boolean replaceWrapping(WrappedKey key) {
        return jdbc.update("UPDATE patient_key SET kek_id = ?, iv = ?, wrapped = ?, rewrapped_at = now() "
                + "WHERE patient_id = ? AND version = ?", key.kekId(), key.iv(), key.wrapped(), key.patientId(), key.version()) == 1;
    }

    @Override
    public boolean delete(String patientId) {
        boolean deleted = jdbc.update("DELETE FROM patient_key WHERE patient_id = ?", patientId) == 1;
        if (deleted) {
            jdbc.update("INSERT INTO patient_key_tombstone (patient_id) VALUES (?) ON CONFLICT DO NOTHING", patientId);
        }
        return deleted;
    }

    @Override
    public boolean isShredded(String patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM patient_key_tombstone WHERE patient_id = ?)", Boolean.class, patientId));
    }

    @Override
    public List<String> wrappedByOtherThan(String currentKekId, String afterPatientId, int limit) {
        return jdbc.queryForList("SELECT patient_id FROM patient_key WHERE kek_id <> ? AND patient_id > ? "
                + "ORDER BY patient_id LIMIT ?", String.class, currentKekId, afterPatientId, limit);
    }
}
