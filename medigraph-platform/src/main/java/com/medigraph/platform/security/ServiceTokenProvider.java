package com.medigraph.platform.security;

/** Supplies this service's own access token (client-credentials) for service-to-service calls. */
@FunctionalInterface
public interface ServiceTokenProvider {

    String token();

    /**
     * Forget the cached token because a receiver rejected it (the issuer restarted with a new key, or revoked it).
     * The next {@link #token()} fetches a fresh one.
     */
    default void invalidate() {
    }

    ServiceTokenProvider NONE = () -> {
        throw new IllegalStateException("no OAuth2 client configured (medigraph.security.client.*)");
    };
}
