package com.medigraph.platform.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code medigraph.http.*}: timeouts and resilience for every service-to-service call.
 * Retry backoff is exponential: initialBackoff, initialBackoff*multiplier, ... (maxAttempts includes the first try).
 * The circuit opens when at least {@code minimumCalls} of the last {@code slidingWindow} calls ran and
 * {@code failureRatePercent} of them failed (5xx / timeout / connection error; 4xx never counts), stays open for
 * {@code openFor}, then lets {@code halfOpenCalls} probes through.
 */
@ConfigurationProperties("medigraph.http")
public record HttpProperties(@DefaultValue("1s") Duration connectTimeout, @DefaultValue("3s") Duration readTimeout,
                             @DefaultValue("3") int maxAttempts, @DefaultValue("100ms") Duration initialBackoff,
                             @DefaultValue("2.0") double backoffMultiplier,
                             @DefaultValue("20") int slidingWindow, @DefaultValue("10") int minimumCalls,
                             @DefaultValue("50") float failureRatePercent, @DefaultValue("10s") Duration openFor,
                             @DefaultValue("3") int halfOpenCalls) {
}
