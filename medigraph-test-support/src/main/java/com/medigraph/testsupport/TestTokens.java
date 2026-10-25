package com.medigraph.testsupport;

import com.medigraph.common.security.Role;
import com.medigraph.platform.security.dev.DevTokenMinter;

import java.time.Duration;
import java.util.Set;

/** One throw-away RS256 key per test JVM; services under test trust it via {@code public-key-pem}. */
public final class TestTokens {

    public static final DevTokenMinter MINTER = new DevTokenMinter("medigraph-dev-issuer", "medigraph");

    private TestTokens() {
    }

    public static String token(String sub, String facility, Role... roles) {
        return MINTER.mint(sub, Set.of(roles), facility, Duration.ofMinutes(30));
    }

    public static String doctor(String id, String facility) {
        return token(id, facility, Role.DOCTOR);
    }

    public static String patient(String healthId) {
        return token(healthId, null, Role.PATIENT);
    }

    public static String steward() {
        return token("steward-1", "MOHFW", Role.STEWARD);
    }

    public static String admin() {
        return token("admin-1", "MOHFW", Role.ADMIN);
    }

    public static String service(String name) {
        return token(name, null, Role.SERVICE);
    }

    public static String publicKeyPem() {
        return MINTER.publicKeyPem();
    }
}
