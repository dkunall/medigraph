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
import java.net.URI;
import java.net.http.HttpClient;
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
 * </ul>
 */
public final class ResilientHttpUpstream implements Upstream {

    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "location", "retry-after",
            "cache-control", "www-authenticate", "idempotent-replayed");

    private final Map<String, URI> services;
    private final HttpClient client;
    private final Duration responseTimeout;
    private final CircuitBreakerRegistry breakers;
    private final CircuitBreakerConfig breakerConfig;
    private final RetryRegistry retries;
    private final RetryConfig retryConfig;

    public ResilientHttpUpstream(Map<String, URI> services, HttpClient client, Duration responseTimeout,
                                 CircuitBreakerRegistry breakers, CircuitBreakerConfig baseBreaker,
                                 RetryRegistry retries, int maxAttempts, Duration initialBackoff) {
        this.services = Map.copyOf(services);
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
        URI base = services.get(target.service());
        if (base == null) {
            throw new UpstreamUnavailableException("no address configured for " + target.service());
        }
        String name = "gateway-" + target.service();
        CircuitBreaker cb = breakers.circuitBreaker(name, breakerConfig);
        Supplier<ProxyResponse> call = CircuitBreaker.decorateSupplier(cb, () -> send(base, request));
        if (request.idempotent()) {
            call = Retry.decorateSupplier(retries.retry(name, retryConfig), call);
        }
        try {
            return call.get();
        } catch (UpstreamIoException e) {
            throw new UpstreamUnavailableException(target.service() + " unreachable", e.getCause());
        }
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
        } catch (IOException e) {
            throw new UpstreamIoException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("interrupted", e);
        }
    }

    /** Unchecked wrapper so the I/O failure can travel through the decorated supplier. */
    static final class UpstreamIoException extends RuntimeException {
        UpstreamIoException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
