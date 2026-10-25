package com.medigraph.service.consent.adapter.out.persistence;

import com.medigraph.common.fhir.DataCategory;
import com.medigraph.consent.BreakGlassGrant;
import com.medigraph.consent.ConsentArtefact;
import com.medigraph.consent.ConsentStore;
import com.medigraph.consent.Purpose;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;




/** Rows are not trusted on read: ConsentService re-verifies the Ed25519 signature before honouring a consent. */
public final class JdbcConsentStore implements ConsentStore {

    private final JdbcTemplate jdbc;

    public JdbcConsentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String join(Set<DataCategory> cats) {
        return cats.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Set<DataCategory> split(String s) {
        EnumSet<DataCategory> out = EnumSet.noneOf(DataCategory.class);
        Arrays.stream(s.split(",")).filter(x -> !x.isBlank()).map(DataCategory::valueOf).forEach(out::add);
        return out;
    }

    private final RowMapper<ConsentArtefact> consent = (rs, n) -> new ConsentArtefact(rs.getString("consent_id"),
            rs.getString("patient_id"), rs.getString("grantee"), split(rs.getString("categories")),
            Purpose.valueOf(rs.getString("purpose")), instant(rs.getTimestamp("issued_at")),
            instant(rs.getTimestamp("expires_at")), rs.getString("signer_key_id"), rs.getString("signature"),
            instant(rs.getTimestamp("revoked_at")));

    private final RowMapper<BreakGlassGrant> grant = (rs, n) -> new BreakGlassGrant(rs.getString("grant_id"),
            rs.getString("patient_id"), rs.getString("requester"), rs.getString("facility"), rs.getString("reason"),
            rs.getString("reason_hash"), split(rs.getString("scope")), instant(rs.getTimestamp("granted_at")),
            instant(rs.getTimestamp("expires_at")));

    @Override
    public void save(ConsentArtefact a) {
        jdbc.update("INSERT INTO consent (consent_id, patient_id, grantee, categories, purpose, issued_at, expires_at, "
                        + "signer_key_id, signature, revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                a.consentId(), a.patientId(), a.grantee(), join(a.categories()), a.purpose().name(), ts(a.issuedAt()),
                ts(a.expiresAt()), a.signerKeyId(), a.signature(), ts(a.revokedAt()));
    }

    @Override
    public Optional<ConsentArtefact> find(String consentId) {
        return jdbc.query("SELECT * FROM consent WHERE consent_id = ?", consent, consentId).stream().findFirst();
    }

    @Override
    public List<ConsentArtefact> forPatient(String patientId) {
        return jdbc.query("SELECT * FROM consent WHERE patient_id = ? ORDER BY issued_at", consent, patientId);
    }

    @Override
    public boolean revoke(String consentId, Instant at) {
        return jdbc.update("UPDATE consent SET revoked_at = ? WHERE consent_id = ? AND revoked_at IS NULL",
                ts(at), consentId) == 1;
    }

    @Override
    public void saveBreakGlass(BreakGlassGrant g) {
        jdbc.update("INSERT INTO break_glass (grant_id, patient_id, requester, facility, reason, reason_hash, scope, "
                        + "granted_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?)", g.grantId(), g.patientId(), g.requester(),
                g.facility(), g.reason(), g.reasonHash(), join(g.scope()), ts(g.grantedAt()), ts(g.expiresAt()));
    }

    @Override
    public List<BreakGlassGrant> breakGlassFor(String patientId) {
        return jdbc.query("SELECT * FROM break_glass WHERE patient_id = ? ORDER BY granted_at", grant, patientId);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
