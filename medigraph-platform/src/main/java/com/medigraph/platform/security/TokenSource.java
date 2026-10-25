package com.medigraph.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Which bearer token an outgoing call carries. */
@FunctionalInterface
public interface TokenSource {

    Optional<String> bearer();

    /** Forward the end user's token (the downstream applies its own rules to the real caller). */
    static TokenSource relayCaller() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            return a instanceof JwtAuthenticationToken j ? Optional.of(j.getToken().getTokenValue()) : Optional.empty();
        };
    }

    /** Call as this service (client-credentials, SERVICE role). */
    static TokenSource service(ServiceTokenProvider tokens) {
        return () -> Optional.of(tokens.token());
    }

    TokenSource NONE = Optional::empty;
}
