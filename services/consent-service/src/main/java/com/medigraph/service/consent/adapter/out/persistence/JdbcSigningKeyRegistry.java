package com.medigraph.service.consent.adapter.out.persistence;

import com.medigraph.service.consent.application.port.out.SigningKeyRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JdbcSigningKeyRegistry implements SigningKeyRegistry {

    private final JdbcTemplate jdbc;

    public JdbcSigningKeyRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: every replica and every restart registers the same key id for the same PEM. */
    @Override
    public void register(String keyId, PublicKey key) {
        jdbc.update("INSERT INTO consent_signing_key (key_id, algorithm, public_key) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                keyId, key.getAlgorithm(), key.getEncoded());
    }

    @Override
    public Map<String, PublicKey> all() {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        jdbc.query("SELECT key_id, algorithm, public_key FROM consent_signing_key ORDER BY created_at", rs -> {
            try {
                out.put(rs.getString(1), KeyFactory.getInstance(rs.getString(2))
                        .generatePublic(new X509EncodedKeySpec(rs.getBytes(3))));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("unreadable signing key " + rs.getString(1), e);
            }
        });
        return out;
    }
}
