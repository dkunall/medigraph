package com.medigraph.platform.http;

import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.platform.security.TokenSource;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.medigraph.common.error.AccessDeniedException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** After the issuer rotates its key, a cached service token gets 401s; the client must renew it, not keep failing. */
class DownstreamTokenRenewalTest {

    private HttpServer server;
    private volatile String accepted = "new";
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            boolean ok = ("Bearer " + accepted).equals(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = (ok ? "{\"ok\":true}" : "{\"detail\":\"invalid bearer token\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ok ? 200 : 401, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Hands out "old" until invalidated, then "new" (what a fresh client-credentials grant would return). */
    private static final class RotatingProvider implements ServiceTokenProvider {
        volatile String current = "old";
        int invalidations;

        @Override
        public String token() {
            return current;
        }

        @Override
        public void invalidate() {
            invalidations++;
            current = "new";
        }
    }

    private Downstream downstream(TokenSource tokens) {
        HttpProperties p = new HttpProperties(Duration.ofSeconds(1), Duration.ofSeconds(2), 3, Duration.ofMillis(10),
                2.0, 20, 10, 50, Duration.ofSeconds(10), 3);
        return new DownstreamFactory(RestClient.builder(), p, CircuitBreakerRegistry.of(DownstreamFactory.breakerConfig(p)),
                RetryRegistry.of(DownstreamFactory.retryConfig(p)))
                .create("peer", "http://127.0.0.1:" + server.getAddress().getPort(), tokens);
    }

    @Test
    void aRejectedServiceTokenIsRenewedOnceAndTheCallSucceeds() {
        RotatingProvider provider = new RotatingProvider();

        String body = downstream(TokenSource.service(provider)).post("/x", "{}", String.class, null);

        assertThat(body).contains("\"ok\":true");
        assertThat(provider.invalidations).isEqualTo(1);
        assertThat(hits).hasValue(2);
    }

    @Test
    void aRejectedUserTokenIsNotRetried() {
        accepted = "something-else";

        assertThatThrownBy(() -> downstream(() -> java.util.Optional.of("user-token")).get("/x", String.class))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(hits).hasValue(1);
    }
}
