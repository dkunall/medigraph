package com.medigraph.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

import java.util.Collection;

/**
 * Every service is an OAuth2 resource server: stateless, bearer JWT (RS256) only, issuer + audience + expiry
 * checked, the {@code roles} claim mapped to {@code ROLE_*} authorities, and {@code @PreAuthorize} enabled for
 * method-level rules. Health probes, Prometheus scrape and OpenAPI docs are the only anonymous endpoints
 * (in a real deployment the scrape/docs ports are not exposed publicly). {@code /internal/**} additionally requires
 * the SERVICE role at the URL level, as defence in depth behind the method rules.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MedigraphSecurityProperties.class)
@EnableMethodSecurity
public class PlatformSecurityAutoConfiguration {

    public static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/actuator/info",
            "/actuator/prometheus", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"};

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(MedigraphSecurityProperties props) {
        MedigraphSecurityProperties.Jwt cfg = props.jwt();
        NimbusJwtDecoder decoder;
        if (cfg.publicKeyPem() != null && !cfg.publicKeyPem().isBlank()) {
            decoder = NimbusJwtDecoder.withPublicKey(Pem.parseRsaPublicKey(cfg.publicKeyPem()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } else if (cfg.jwkSetUri() != null && !cfg.jwkSetUri().isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(cfg.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            throw new IllegalStateException("set medigraph.security.jwt.jwk-set-uri (or public-key-pem for tests)");
        }
        decoder.setJwtValidator(validator(cfg.issuer(), cfg.audience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(roles);
        return c;
    }

    @Bean
    public ProblemAuthHandlers problemAuthHandlers(ObjectMapper json) {
        return new ProblemAuthHandlers(json);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain medigraphSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                                            ProblemAuthHandlers handlers) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ServiceTokenProvider serviceTokenProvider(MedigraphSecurityProperties props, RestClient.Builder builder) {
        if (!props.client().configured()) {
            return ServiceTokenProvider.NONE;
        }
        return new ClientCredentialsTokenProvider(props.client(), builder.build(), java.time.Clock.systemUTC());
    }
}
