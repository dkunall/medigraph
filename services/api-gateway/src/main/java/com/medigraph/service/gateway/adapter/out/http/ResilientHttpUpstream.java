package com.medigraph.service.gateway.adapter.out.http;

import com.medigraph.common.error.UpstreamUnavailableException;
import com.medigraph.service.gateway.application.ProxyRequest;
import com.medigraph.service.gateway.application.ProxyResponse;
import com.medigraph.service.gateway.application.port.out.Upstream;
import com.medigraph.service.gateway.domain.RouteTarget;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Relays a request with the JDK HTTP client, guarded per backend by Resilience4j:
 * <ul>
 *   <li>timeouts: connect timeout on the client, response timeout per request;</li>
 *   <li>circuit breaker {@code gateway-<service>}: I/O failures and 5xx answers count as failures; while open the
 *       gateway answers 503 at once instead of piling requests onto a sick service;</li>
 *   <li>retry with exponential backoff, only for idempotent methods (GET/HEAD/OPTIONS) and only on I/O failures,
 *       never on a 5xx answer (the backend may have done the work). POSTs are not retried here; clients retry them
 *       with an {@code Idempotency-Key}, which the services de-duplicate.</li>
 *   <li>replicas: a service may list several base URLs (the Raft ledger does). The gateway sticks to the one that
 *       last answered and moves to the next when a connection cannot be opened or a node answers 421 (not the
 *       leader). Both mean the request was not processed, so this is safe for POSTs too.</li>
 * </ul>
 */
public final class ResilientHttpUpstream implements Upstream {

    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "location", "retry-after",
            "cache-control", "www-authenticate", "idempotent-replayed");

    private static final int MISDIRECTED = 421;

    private final Map<String, ReplicaSet> services;
    private final HttpClient client;
    private final Duration responseTimeout;
    private final CircuitBreakerRegistry breakers;
    private final CircuitBreakerConfig breakerConfig;
    private final RetryRegistry retries;
    private final RetryConfig retryConfig;

    public ResilientHttpUpstream(Map<String, List<URI>> services, HttpClient client, Duration responseTimeout,
                                 CircuitBreakerRegistry breakers, CircuitBreakerConfig baseBreaker,
                                 RetryRegistry retries, int maxAttempts, Duration initialBackoff) {
        Map<String, ReplicaSet> sets = new HashMap<>();
        services.forEach((name, urls) -> sets.put(name, new ReplicaSet(urls)));
        this.services = Map.copyOf(sets);
        this.client = client;
        this.responseTimeout = responseTimeout;
        this.breakers = breakers;
        this.breakerConfig = CircuitBreakerConfig.from(baseBreaker)
                .recordExceptions(IOException.class, UpstreamIoException.class)
                .recordResult(r -> r instanceof ProxyResponse p && p.status() >= 500)
                .build();
        this.retries = retries;
        this.retryConfig = RetryConfig.custom().maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoff, 2.0))
                .retryExceptions(UpstreamIoException.class)
                .build();
    }

    @Override
    public ProxyResponse forward(RouteTarget target, ProxyRequest request) {
        ReplicaSet replicas = services.get(target.service());
        if (replicas == null) {
            throw new UpstreamUnavailableException("no address configured for " + target.service());
        }
        String name = "gateway-" + target.service();
        CircuitBreaker cb = breakers.circuitBreaker(name, breakerConfig);
        Supplier<ProxyResponse> call = CircuitBreaker.decorateSupplier(cb, () -> sendToReplicas(replicas, request));
        if (request.idempotent()) {
            call = Retry.decorateSupplier(retries.retry(name, retryConfig), call);
        }
        try {
            return call.get();
        } catch (UpstreamIoException e) {
            throw new UpstreamUnavailableException(target.service() + " unreachable", e.getCause());
        }
    }

    private ProxyResponse sendToReplicas(ReplicaSet replicas, ProxyRequest r) {
        int start = replicas.preferred();
        int n = replicas.size();
        ProxyResponse misdirected = null;
        IOException notConnected = null;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            try {
                ProxyResponse res = send(replicas.get(idx), r);
                if (res.status() == MISDIRECTED && n > 1) {
                    misdirected = res;
                    continue;
                }
                replicas.prefer(idx);
                return res;
            } catch (NotConnectedException e) {
                notConnected = e.io;
            }
        }
        if (misdirected != null) {
            return misdirected;
        }
        throw new UpstreamIoException(notConnected);
    }

    private ProxyResponse send(URI base, ProxyRequest r) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + r.pathAndQuery()))
                .timeout(responseTimeout)
                .method(r.method(), r.body().length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(r.body()));
        r.headers().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
        try {
            HttpResponse<byte[]> res = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, List<String>> headers = new HashMap<>();
            res.headers().map().forEach((k, v) -> {
                String key = k.toLowerCase(Locale.ROOT);
                if (RESPONSE_HEADERS.contains(key)) {
                    headers.put(key, List.copyOf(v));
                }
            });
            return new ProxyResponse(res.statusCode(), headers, res.body());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new NotConnectedException(e);
        } catch (IOException e) {
            throw new UpstreamIoException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("interrupted", e);
        }
    }

    /** The connection was never opened, so the request was not delivered and another replica may take it. */
    private static final class NotConnectedException extends RuntimeException {
        final IOException io;

        NotConnectedException(IOException io) {
            super(io.getMessage(), io, false, false);
            this.io = io;
        }
    }

    /** Unchecked wrapper so the I/O failure can travel through the decorated supplier. */
    static final class UpstreamIoException extends RuntimeException {
        UpstreamIoException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
