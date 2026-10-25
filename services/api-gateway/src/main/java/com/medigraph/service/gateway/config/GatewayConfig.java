package com.medigraph.service.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.platform.http.DownstreamFactory;
import com.medigraph.platform.http.HttpProperties;
import com.medigraph.platform.security.MedigraphSecurityProperties;
import com.medigraph.platform.security.PlatformSecurityAutoConfiguration;
import com.medigraph.platform.security.ProblemAuthHandlers;
import com.medigraph.platform.security.dev.DevIssuer;
import com.medigraph.platform.security.dev.DevTokenMinter;
import com.medigraph.service.gateway.adapter.in.web.RateLimitFilter;
import com.medigraph.service.gateway.adapter.out.http.ResilientHttpUpstream;
import com.medigraph.service.gateway.application.GatewayProxy;
import com.medigraph.service.gateway.application.RateLimiter;
import com.medigraph.service.gateway.application.RouteTable;
import com.medigraph.service.gateway.application.port.out.Upstream;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
public class GatewayConfig {

    @Bean
    RouteTable routeTable(GatewayProperties p) {
        Map<String, String> routes = new LinkedHashMap<>();
        Objects.requireNonNull(p.routes(), "medigraph.gateway.routes").forEach(r -> routes.put(r.pattern(), r.service()));
        return new RouteTable(routes);
    }

    @Bean
    Upstream upstream(GatewayProperties p, HttpProperties http, CircuitBreakerRegistry breakers, RetryRegistry retries) {
        Map<String, List<URI>> services = new LinkedHashMap<>();
        Objects.requireNonNull(p.services(), "medigraph.gateway.services").forEach((k, v) -> services.put(k,
                Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(URI::create).toList()));
        HttpClient client = HttpClient.newBuilder().connectTimeout(http.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
        return new ResilientHttpUpstream(services, client, p.responseTimeout(), breakers,
                DownstreamFactory.breakerConfig(http), retries, http.maxAttempts(), http.initialBackoff());
    }

    @Bean
    GatewayProxy gatewayProxy(RouteTable routes, Upstream upstream) {
        return new GatewayProxy(routes, upstream);
    }

    @Bean
    RateLimiter rateLimiter(GatewayProperties p) {
        GatewayProperties.RateLimit r = p.rateLimit();
        return new RateLimiter(r.capacity(), r.refillPerSecond(), r.maxClients(), System::nanoTime);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, ObjectMapper json) {
        FilterRegistrationBean<RateLimitFilter> r = new FilterRegistrationBean<>(new RateLimitFilter(limiter, json));
        r.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 5); // after authentication: limit per JWT subject
        return r;
    }

    /** Edge rules: probes/docs/dev-issuer public, {@code /internal/**} never exposed, {@code /api/**} needs a JWT. */
    @Bean
    SecurityFilterChain gatewaySecurity(HttpSecurity http, JwtAuthenticationConverter converter,
                                        ProblemAuthHandlers handlers) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PlatformSecurityAutoConfiguration.PUBLIC_PATHS).permitAll()
                        .requestMatchers("/oauth2/token", "/.well-known/jwks.json", "/dev/token").permitAll()
                        .requestMatchers("/internal/**").denyAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    /** Local-dev issuer wiring: a fresh in-memory RSA key per start; the gateway validates its own tokens with it. */
    @Configuration
    @ConditionalOnProperty(prefix = "medigraph.gateway.dev-issuer", name = "enabled", havingValue = "true")
    static class DevIssuerConfig {

        @Bean
        DevTokenMinter devTokenMinter(MedigraphSecurityProperties sec) {
            return new DevTokenMinter(sec.jwt().issuer(), sec.jwt().audience());
        }

        @Bean
        DevIssuer devIssuer(DevTokenMinter minter, GatewayProperties p) {
            Map<String, String> clients = p.devIssuer().clients() == null ? Map.of() : p.devIssuer().clients();
            return new DevIssuer(minter, clients);
        }

        @Bean
        JwtDecoder jwtDecoder(DevTokenMinter minter, MedigraphSecurityProperties sec) {
            NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(minter.publicKey()).build();
            d.setJwtValidator(PlatformSecurityAutoConfiguration.validator(sec.jwt().issuer(), sec.jwt().audience()));
            return d;
        }
    }
}
