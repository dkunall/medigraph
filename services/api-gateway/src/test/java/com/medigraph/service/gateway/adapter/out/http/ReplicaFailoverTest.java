package com.medigraph.service.gateway.adapter.out.http;

import com.medigraph.service.gateway.application.ProxyRequest;
import com.medigraph.service.gateway.application.ProxyResponse;
import com.medigraph.service.gateway.domain.RouteTarget;
import com.medigraph.testsupport.FreePorts;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A replicated backend (the Raft ledger): followers answer 421, dead nodes refuse connections. */
class ReplicaFailoverTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private record Node(URI url, AtomicInteger hits) {
    }

    private Node node(int status) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        s.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] out = ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        servers.add(s);
        return new Node(URI.create("http://127.0.0.1:" + s.getAddress().getPort()), hits);
    }

    private static ResilientHttpUpstream upstream(List<URI> urls) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        return new ResilientHttpUpstream(Map.of("ledger", urls), client, Duration.ofSeconds(2),
                CircuitBreakerRegistry.ofDefaults(), CircuitBreakerConfig.ofDefaults(), RetryRegistry.ofDefaults(),
                1, Duration.ofMillis(10));
    }

    private static ProxyRequest post() {
        return new ProxyRequest("POST", "/internal/ledger/entries", null, Map.of(), "[]".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void followerAnswersAreSkippedAndTheLeaderIsRemembered() throws IOException {
        Node follower = node(421);
        Node leader = node(200);
        ResilientHttpUpstream up = upstream(List.of(follower.url(), leader.url()));

        assertThat(up.forward(new RouteTarget("ledger"), post()).status()).isEqualTo(200);
        assertThat(up.forward(new RouteTarget("ledger"), post()).status()).isEqualTo(200);

        assertThat(follower.hits()).hasValue(1);   // second call went straight to the leader
        assertThat(leader.hits()).hasValue(2);
    }

    @Test
    void aDeadNodeIsSkippedEvenForPosts() throws IOException {
        Node live = node(201);
        URI dead = URI.create("http://127.0.0.1:" + FreePorts.next());
        ResilientHttpUpstream up = upstream(List.of(dead, live.url()));

        assertThat(up.forward(new RouteTarget("ledger"), post()).status()).isEqualTo(201);
        assertThat(live.hits()).hasValue(1);
    }

    @Test
    void whenNoReplicaLeadsTheLast421IsRelayed() throws IOException {
        Node a = node(421);
        Node b = node(421);
        ProxyResponse r = upstream(List.of(a.url(), b.url())).forward(new RouteTarget("ledger"), post());

        assertThat(r.status()).isEqualTo(421);
        assertThat(a.hits().get() + b.hits().get()).isEqualTo(2);
    }
}
