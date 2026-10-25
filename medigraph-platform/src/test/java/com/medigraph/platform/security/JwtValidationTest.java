package com.medigraph.platform.security;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import com.medigraph.platform.security.dev.DevIssuer;
import com.medigraph.platform.security.dev.DevTokenMinter;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationTest {

    private static final String ISS = "medigraph-dev-issuer";
    private static final String AUD = "medigraph";
    private final DevTokenMinter minter = new DevTokenMinter(ISS, AUD);

    private NimbusJwtDecoder decoderFor(DevTokenMinter trusted) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(Pem.parseRsaPublicKey(trusted.publicKeyPem()))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        d.setJwtValidator(PlatformSecurityAutoConfiguration.validator(ISS, AUD));
        return d;
    }

    @Test
    void validTokenDecodesToActor() {
        String token = minter.mint("dr-1", Set.of(Role.DOCTOR), "AIIMS-DEL", Duration.ofMinutes(5));
        Jwt jwt = decoderFor(minter).decode(token);
        Actor a = CurrentActorArgumentResolver.fromJwt(jwt);
        assertThat(a.id()).isEqualTo("dr-1");
        assertThat(a.roles()).containsExactly(Role.DOCTOR);
        assertThat(a.facility()).isEqualTo("AIIMS-DEL");
    }

    @Test
    void wrongIssuerAudienceOrExpiryAreRejected() {
        NimbusJwtDecoder decoder = decoderFor(minter);
        String wrongIss = minter.mint("u", List.of("DOCTOR"), "F1", Duration.ofMinutes(5), "evil-issuer", AUD);
        String wrongAud = minter.mint("u", List.of("DOCTOR"), "F1", Duration.ofMinutes(5), ISS, "another-api");
        DevTokenMinter past = new DevTokenMinter(ISS, AUD, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        NimbusJwtDecoder pastDecoder = decoderFor(past);
        String expired = past.mint("u", Set.of(Role.DOCTOR), "F1", Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(wrongIss)).isInstanceOf(JwtException.class).hasMessageContaining("iss");
        assertThatThrownBy(() -> decoder.decode(wrongAud)).isInstanceOf(JwtException.class).hasMessageContaining("aud");
        assertThatThrownBy(() -> pastDecoder.decode(expired)).isInstanceOf(JwtException.class).hasMessageContaining("xpired");
    }

    @Test
    void tokenSignedByAnotherKeyOrUnsignedIsRejected() {
        DevTokenMinter attacker = new DevTokenMinter(ISS, AUD);
        String forged = attacker.mint("admin", Set.of(Role.ADMIN), "F1", Duration.ofMinutes(5));
        assertThatThrownBy(() -> decoderFor(minter).decode(forged)).isInstanceOf(JwtException.class);

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String none = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"sub\":\"admin\",\"iss\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"roles\":[\"ADMIN\"]}")
                .getBytes(StandardCharsets.UTF_8)) + ".";
        assertThatThrownBy(() -> decoderFor(minter).decode(none)).isInstanceOf(JwtException.class);
    }

    @Test
    void unknownRolesGrantNothingAndStaffWithoutFacilityIsDenied() {
        String unknownOnly = minter.mint("u", List.of("SUPERUSER"), "F1", Duration.ofMinutes(5), ISS, AUD);
        Jwt jwt = decoderFor(minter).decode(unknownOnly);
        assertThatThrownBy(() -> CurrentActorArgumentResolver.fromJwt(jwt)).isInstanceOf(AccessDeniedException.class);

        String noFacility = minter.mint("dr", Set.of(Role.DOCTOR), null, Duration.ofMinutes(5));
        Jwt jwt2 = decoderFor(minter).decode(noFacility);
        assertThatThrownBy(() -> CurrentActorArgumentResolver.fromJwt(jwt2)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void devIssuerClientCredentialsChecksTheSecret() {
        DevIssuer issuer = new DevIssuer(minter, Map.of("records-service", "dev-only-records-secret"));
        Base64.Encoder b64 = Base64.getEncoder();
        String good = "Basic " + b64.encodeToString("records-service:dev-only-records-secret".getBytes(StandardCharsets.UTF_8));
        String bad = "Basic " + b64.encodeToString("records-service:guess".getBytes(StandardCharsets.UTF_8));
        String unknown = "Basic " + b64.encodeToString("nobody:dev-only-records-secret".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> resp = issuer.clientCredentials(good).orElseThrow();
        Jwt jwt = decoderFor(minter).decode((String) resp.get("access_token"));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(issuer.clientCredentials(bad)).isEmpty();
        assertThat(issuer.clientCredentials(unknown)).isEmpty();
        assertThat(issuer.clientCredentials(null)).isEmpty();
    }
}
