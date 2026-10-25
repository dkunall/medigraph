package com.medigraph.service.consent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * {@code medigraph.consent.*}. {@code signingKeyPath} is the Ed25519 PEM; {@code generateKeyIfMissing} is a
 * development convenience and must be false in production (mount the key from a secret store instead).
 */
@ConfigurationProperties("medigraph.consent")
public record ConsentProperties(@DefaultValue("./data/consent-signing-key.pem") Path signingKeyPath,
                                @DefaultValue("true") boolean generateKeyIfMissing,
                                @DefaultValue("PT4H") Duration breakGlassWindow,
                                @DefaultValue("P365D") Duration maxConsentDuration,
                                @DefaultValue("http://localhost:8081") String identityUrl,
                                @DefaultValue("30s") Duration identityCacheTtl) {
}
