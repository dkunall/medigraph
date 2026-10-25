package com.medigraph.service.identity.adapter.out.persistence;

import com.medigraph.mpi.PatientDemographics;
import com.medigraph.service.identity.application.port.out.PatientDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcPatientDirectory implements PatientDirectory {

    private final JdbcTemplate jdbc;

    public JdbcPatientDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertPatient(String healthId) {
        return jdbc.update("INSERT INTO patient (health_id) VALUES (?) ON CONFLICT DO NOTHING", healthId) == 1;
    }

    @Override
    public boolean exists(String healthId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM patient WHERE health_id = ?", Integer.class, healthId);
        return n != null && n > 0;
    }

    @Override
    public void insertRegistration(PatientDemographics p, String healthId, String localMrn, String decision, Double weight) {
        jdbc.update("INSERT INTO patient_registration (record_id, health_id, facility, local_mrn, given_name, family_name, "
                        + "birth_date, gender, phone, city, match_decision, match_weight) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                p.recordId(), healthId, p.facility(), localMrn, p.givenName(), p.familyName(),
                p.birthDate() == null ? null : Date.valueOf(p.birthDate()), p.gender(), p.phone(), p.city(), decision, weight);
    }

    @Override
    public void insertLink(String a, String b, String reason) {
        jdbc.update("INSERT INTO mpi_link (record_a, record_b, reason) VALUES (?,?,?) ON CONFLICT DO NOTHING", a, b, reason);
    }

    @Override
    public Optional<String> healthIdOfRecord(String recordId) {
        return jdbc.queryForList("SELECT health_id FROM patient_registration WHERE record_id = ?", String.class, recordId)
                .stream().findFirst();
    }

    /** Recursive CTE over merged_into; depth-capped at 32 so a corrupted cycle cannot loop forever. */
    @Override
    public String canonical(String healthId) {
        return jdbc.queryForObject("""
                WITH RECURSIVE chain(id, next, depth) AS (
                    SELECT health_id, merged_into, 0 FROM patient WHERE health_id = ?
                    UNION ALL
                    SELECT p.health_id, p.merged_into, c.depth + 1 FROM patient p JOIN chain c ON p.health_id = c.next
                    WHERE c.depth < 32)
                SELECT id FROM chain WHERE next IS NULL""", String.class, healthId);
    }

    @Override
    public List<String> aliases(String canonicalId) {
        return jdbc.queryForList("""
                WITH RECURSIVE a(id) AS (
                    SELECT health_id FROM patient WHERE health_id = ?
                    UNION
                    SELECT p.health_id FROM patient p JOIN a ON p.merged_into = a.id)
                SELECT id FROM a ORDER BY id""", String.class, canonicalId);
    }

    @Override
    public void merge(String fromHealthId, String intoHealthId) {
        jdbc.update("UPDATE patient SET merged_into = ? WHERE health_id = ?", intoHealthId, fromHealthId);
    }

    @Override
    public List<RegistrationRow> registrations(List<String> healthIds) {
        return jdbc.query("SELECT record_id, health_id, facility, local_mrn, match_decision, match_weight, created_at, "
                        + "erased_at FROM patient_registration WHERE health_id = ANY (?) ORDER BY created_at",
                (rs, n) -> new RegistrationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Double) rs.getObject(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8))),
                (Object) healthIds.toArray(String[]::new));
    }

    @Override
    public List<String> recordIds(List<String> healthIds) {
        return jdbc.queryForList("SELECT record_id FROM patient_registration WHERE health_id = ANY (?)", String.class,
                (Object) healthIds.toArray(String[]::new));
    }

    @Override
    public int eraseDemographics(List<String> healthIds) {
        return jdbc.update("UPDATE patient_registration SET given_name = NULL, family_name = NULL, birth_date = NULL, "
                + "gender = NULL, phone = NULL, city = NULL, erased_at = now() WHERE erased_at IS NULL "
                + "AND health_id = ANY (?)", (Object) healthIds.toArray(String[]::new));
    }

    @Override
    public List<PatientDemographics> allLiveDemographics() {
        return jdbc.query("SELECT * FROM patient_registration WHERE erased_at IS NULL", (rs, n) -> new PatientDemographics(
                rs.getString("record_id"), rs.getString("given_name"), rs.getString("family_name"),
                rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toLocalDate(), rs.getString("gender"),
                rs.getString("phone"), rs.getString("city"), rs.getString("facility")));
    }

    @Override
    public List<Map.Entry<String, String>> allLinks() {
        return jdbc.query("SELECT record_a, record_b FROM mpi_link",
                (rs, n) -> new AbstractMap.SimpleImmutableEntry<>(rs.getString(1), rs.getString(2)));
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
