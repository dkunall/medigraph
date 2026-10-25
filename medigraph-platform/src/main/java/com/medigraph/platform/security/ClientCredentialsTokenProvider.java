package com.medigraph.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;

/**
 * OAuth2 client-credentials grant with an in-memory cache: the token is reused until {@code refreshSkew} before it
 * expires, so the issuer sees one request per service per token lifetime, not one per call.
 */
public final class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    private final MedigraphSecurityProperties.Client cfg;
    private final RestClient http;
    private final Clock clock;
    private String cached;
    private Instant refreshAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(MedigraphSecurityProperties.Client cfg, RestClient http, Clock clock) {
        this.cfg = cfg;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public synchronized String token() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(refreshAt)) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            JsonNode res = http.post().uri(cfg.tokenUri())
                    .headers(h -> h.setBasicAuth(cfg.id(), cfg.secret() == null ? "" : cfg.secret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(JsonNode.class);
            if (res == null || !res.hasNonNull("access_token")) {
                throw new IllegalStateException("token endpoint returned no access_token");
            }
            cached = res.get("access_token").asText();
            long ttl = res.path("expires_in").asLong(300);
            refreshAt = now.plusSeconds(ttl).minus(cfg.refreshSkew());
        }
        return cached;
    }

    @Override
    public synchronized void invalidate() {
        cached = null;
    }
}
