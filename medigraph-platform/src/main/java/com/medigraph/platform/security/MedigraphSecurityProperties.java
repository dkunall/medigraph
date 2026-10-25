package com.medigraph.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code medigraph.security.*}.
 *
 * <p>jwt: tokens are verified with RS256 against {@code jwkSetUri} (the issuer's JWKS endpoint) or, for tests, a
 * fixed {@code publicKeyPem}; {@code issuer} and {@code audience} must match.
 * <p>client: this service's own OAuth2 client-credentials registration, used for service-to-service calls.
 * The secret is injected from the environment; only obvious local-dev values appear in docker-compose / local config.
 */
@ConfigurationProperties("medigraph.security")
public record MedigraphSecurityProperties(@DefaultValue Jwt jwt, @DefaultValue Client client) {

    public record Jwt(@DefaultValue("medigraph-dev-issuer") String issuer, @DefaultValue("medigraph") String audience,
                      String jwkSetUri, String publicKeyPem) {
    }

    public record Client(String id, String secret, String tokenUri, @DefaultValue("30s") Duration refreshSkew) {
        public boolean configured() {
            return id != null && !id.isBlank() && tokenUri != null && !tokenUri.isBlank();
        }
    }
}
