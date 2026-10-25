package com.medigraph.platform.http;

import com.medigraph.platform.security.TokenSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds {@link Downstream}s that share registries (so breakers/retries show up in /actuator/prometheus as
 * {@code resilience4j_circuitbreaker_*} / {@code resilience4j_retry_*}) and carry tracing via the Boot-provided
 * {@link RestClient.Builder} (observations → W3C traceparent on every call).
 */
public final class DownstreamFactory {

    private final RestClient.Builder builder;
    private final HttpProperties props;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public DownstreamFactory(RestClient.Builder builder, HttpProperties props, CircuitBreakerRegistry breakers,
                             RetryRegistry retries) {
        this.builder = builder;
        this.props = props;
        this.breakers = breakers;
        this.retries = retries;
    }

    public static CircuitBreakerConfig breakerConfig(HttpProperties p) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(p.slidingWindow()).minimumNumberOfCalls(p.minimumCalls())
                .failureRateThreshold(p.failureRatePercent()).waitDurationInOpenState(p.openFor())
                .permittedNumberOfCallsInHalfOpenState(p.halfOpenCalls())
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build();
    }

    public static RetryConfig retryConfig(HttpProperties p) {
        return RetryConfig.custom().maxAttempts(p.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(p.initialBackoff(), p.backoffMultiplier()))
                .retryOnException(t -> t instanceof ResourceAccessException
                        || (t instanceof HttpServerErrorException h && h.getStatusCode().value() != 501))
                .build();
    }

    public Downstream create(String name, String baseUrl, TokenSource tokens) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdk);
        rf.setReadTimeout(props.readTimeout());
        RestClient client = builder.clone().baseUrl(baseUrl).requestFactory(rf).build();
        CircuitBreaker cb = breakers.circuitBreaker(name);
        Retry retry = retries.retry(name);
        return new Downstream(name, client, retry, cb, tokens);
    }
}
