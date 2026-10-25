package com.medigraph.service.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * @param services   logical service name → base URL, or a comma-separated list of replica URLs
 * @param routes     path pattern → service (see {@code PathTrie} for matching)
 */
@ConfigurationProperties("medigraph.gateway")
public record GatewayProperties(Map<String, String> services, List<Route> routes,
                                @DefaultValue RateLimit rateLimit, @DefaultValue DevIssuer devIssuer,
                                @DefaultValue("5s") Duration responseTimeout) {

    public record Route(String pattern, String service) {
    }

    public record RateLimit(@DefaultValue("100") int capacity, @DefaultValue("50") double refillPerSecond,
                            @DefaultValue("10000") int maxClients) {
    }

    /** Local-dev issuer; {@code clients} maps client id → dev-only secret. */
    public record DevIssuer(@DefaultValue("false") boolean enabled, Map<String, String> clients) {
    }
}
