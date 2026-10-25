package com.medigraph.platform.security.dev;

import com.medigraph.common.security.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DEVELOPMENT issuer logic shared by the gateway's dev endpoints and the stand-alone test server:
 * the OAuth2 client-credentials grant for registered service clients (SERVICE role) and a "mint me a user token"
 * helper for local testing. Client secrets come from configuration (local-dev values only); they are compared in
 * constant time.
 */
public final class DevIssuer {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final DevTokenMinter minter;
    private final Map<String, String> clientSecrets;

    public DevIssuer(DevTokenMinter minter, Map<String, String> clientSecrets) {
        this.minter = minter;
        this.clientSecrets = Map.copyOf(clientSecrets);
    }

    public DevTokenMinter minter() {
        return minter;
    }

    /** RFC 6749 4.4 with HTTP Basic client authentication. Empty when the client is unknown or the secret is wrong. */
    public Optional<Map<String, Object>> clientCredentials(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String id = decoded.substring(0, colon);
        String secret = decoded.substring(colon + 1);
        String expected = clientSecrets.get(id);
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(tokenResponse(minter.mint(id, Set.of(Role.SERVICE), null, TOKEN_TTL)));
    }

    public Map<String, Object> userToken(String subject, List<Role> roles, String facility) {
        return tokenResponse(minter.mint(subject, roles, facility, TOKEN_TTL));
    }

    private static Map<String, Object> tokenResponse(String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", token);
        m.put("token_type", "Bearer");
        m.put("expires_in", TOKEN_TTL.toSeconds());
        return m;
    }
}
