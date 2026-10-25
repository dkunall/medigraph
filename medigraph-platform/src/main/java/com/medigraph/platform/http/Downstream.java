package com.medigraph.platform.http;

import com.medigraph.common.error.AccessDeniedException;
import com.medigraph.common.error.ConflictException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.error.UpstreamUnavailableException;
import com.medigraph.platform.security.TokenSource;
import com.medigraph.platform.web.CorrelationIdFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One remote service, called through: timeout (JDK HttpClient connect + read) → circuit breaker → retry with
 * exponential backoff. Retries happen only when repeating is safe: GET/HEAD, or a write carrying an
 * {@code Idempotency-Key} (the downstream replays instead of re-executing). Errors are translated to the
 * shared exception vocabulary so a 404 from consent is a NotFoundException here, and an open circuit or exhausted
 * retries become {@link UpstreamUnavailableException} (→ 503).
 */
public final class Downstream {

    private final String name;
    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final TokenSource tokens;

    Downstream(String name, RestClient client, Retry retry, CircuitBreaker breaker, TokenSource tokens) {
        this.name = name;
        this.client = client;
        this.retry = retry;
        this.breaker = breaker;
        this.tokens = tokens;
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public <T> T get(String uri, Class<T> type, Object... vars) {
        return call(HttpMethod.GET, uri, null, type, null, vars);
    }

    public <T> T post(String uri, Object body, Class<T> type, String idempotencyKey, Object... vars) {
        return call(HttpMethod.POST, uri, body, type, idempotencyKey, vars);
    }

    public <T> T call(HttpMethod method, String uri, Object body, Class<T> type, String idempotencyKey, Object... vars) {
        // resolve caller-bound values on the calling thread (security context, MDC)
        Optional<String> bearer = tokens.bearer();
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        Supplier<T> once = () -> {
            RestClient.RequestBodySpec spec = client.method(method).uri(uri, vars).headers(h -> {
                bearer.ifPresent(h::setBearerAuth);
                if (cid != null) {
                    h.set(CorrelationIdFilter.HEADER, cid);
                }
                if (idempotencyKey != null) {
                    h.set("Idempotency-Key", idempotencyKey);
                }
            });
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.retrieve().body(type);
        };
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, once);
        boolean safeToRepeat = method == HttpMethod.GET || method == HttpMethod.HEAD || idempotencyKey != null;
        Supplier<T> attempt = safeToRepeat ? Retry.decorateSupplier(retry, guarded) : guarded;
        try {
            return attempt.get();
        } catch (HttpClientErrorException e) {
            throw translate(e);
        } catch (CallNotPermittedException e) {
            throw new UpstreamUnavailableException(name + " circuit is open", e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(name + " unavailable: " + e.getMessage(), e);
        }
    }

    private RuntimeException translate(HttpClientErrorException e) {
        String detail = detailOf(e);
        return switch (e.getStatusCode().value()) {
            case 404 -> new NotFoundException(detail);
            case 401, 403 -> new AccessDeniedException(detail);
            case 409 -> new ConflictException(detail);
            case 400, 422 -> new IllegalArgumentException(detail);
            case 421 -> new UpstreamUnavailableException(name + " is not the leader", e);
            default -> new UpstreamUnavailableException(name + " returned " + e.getStatusCode(), e);
        };
    }

    private String detailOf(HttpClientErrorException e) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsByteArray());
            if (node != null && node.hasNonNull("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // not a problem+json body
        }
        return name + " returned " + e.getStatusCode().value();
    }
}
