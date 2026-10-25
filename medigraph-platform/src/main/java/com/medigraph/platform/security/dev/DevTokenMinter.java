package com.medigraph.platform.security.dev;

import com.medigraph.common.security.Role;
import com.medigraph.platform.security.Pem;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DEVELOPMENT/TEST token issuer. Generates a fresh RSA-2048 key pair in memory at construction (nothing is read
 * from or written to disk, there is no secret in the repo) and mints RS256 JWTs with the claims the services
 * expect: {@code sub}, {@code roles}, {@code facility}, {@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code jti}.
 * Production replaces this with a real IdP (Keycloak, Entra ID, Okta...) exposing the same claims via its JWKS.
 */
public final class DevTokenMinter {

    private final RSAKey key;
    private final RSASSASigner signer;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public DevTokenMinter(String issuer, String audience, Clock clock) {
        try {
            this.key = new RSAKeyGenerator(2048).keyID("dev-" + UUID.randomUUID().toString().substring(0, 8))
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
            this.signer = new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
    }

    public DevTokenMinter(String issuer, String audience) {
        this(issuer, audience, Clock.systemUTC());
    }

    public String mint(String subject, Collection<Role> roles, String facility, Duration ttl) {
        return mint(subject, roles.stream().map(Enum::name).toList(), facility, ttl, issuer, audience);
    }

    /** Low-level variant (lets tests forge wrong issuer/audience/expired tokens signed by a trusted key). */
    public String mint(String subject, List<String> roles, String facility, Duration ttl, String iss, String aud) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().subject(subject).issuer(iss).audience(aud)
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plus(ttl))).jwtID(UUID.randomUUID().toString()).claim("roles", roles);
        if (facility != null) {
            c.claim("facility", facility);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), c.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    public RSAPublicKey publicKey() {
        try {
            return key.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String publicKeyPem() {
        return Pem.encodePublicKey(publicKey());
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }
}
