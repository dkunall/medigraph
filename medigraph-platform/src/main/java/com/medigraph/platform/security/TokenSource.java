package com.medigraph.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Which bearer token an outgoing call carries. */
@FunctionalInterface
public interface TokenSource {

    Optional<String> bearer();

    /**
     * Called when the receiver answered 401 to a token from this source. Returns true when a fresh token can be
     * fetched, so the call is worth one more try; a relayed user token cannot be renewed here.
     */
    default boolean renewAfterRejection() {
        return false;
    }

    /** Forward the end user's token (the downstream applies its own rules to the real caller). */
    static TokenSource relayCaller() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            return a instanceof JwtAuthenticationToken j ? Optional.of(j.getToken().getTokenValue()) : Optional.empty();
        };
    }

    /** Call as this service (client-credentials, SERVICE role). */
    static TokenSource service(ServiceTokenProvider tokens) {
        return new TokenSource() {
            @Override
            public Optional<String> bearer() {
                return Optional.of(tokens.token());
            }

            @Override
            public boolean renewAfterRejection() {
                tokens.invalidate();
                return true;
            }
        };
    }

    TokenSource NONE = Optional::empty;
}
