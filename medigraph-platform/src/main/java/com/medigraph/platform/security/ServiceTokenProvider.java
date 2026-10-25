package com.medigraph.platform.security;

/** Supplies this service's own access token (client-credentials) for service-to-service calls. */
@FunctionalInterface
public interface ServiceTokenProvider {

    String token();

    ServiceTokenProvider NONE = () -> {
        throw new IllegalStateException("no OAuth2 client configured (medigraph.security.client.*)");
    };
}
