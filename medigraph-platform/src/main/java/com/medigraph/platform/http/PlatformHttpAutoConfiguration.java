package com.medigraph.platform.http;

import com.medigraph.platform.ledger.LedgerClient;
import com.medigraph.platform.ledger.LedgerClientProperties;
import com.medigraph.platform.security.ServiceTokenProvider;
import com.medigraph.platform.security.TokenSource;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration(after = RestClientAutoConfiguration.class)
@EnableConfigurationProperties({HttpProperties.class, LedgerClientProperties.class})
public class PlatformHttpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(HttpProperties p, ObjectProvider<MeterRegistry> meters) {
        CircuitBreakerRegistry r = CircuitBreakerRegistry.of(DownstreamFactory.breakerConfig(p));
        meters.ifAvailable(m -> TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(r).bindTo(m));
        return r;
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry retryRegistry(HttpProperties p, ObjectProvider<MeterRegistry> meters) {
        RetryRegistry r = RetryRegistry.of(DownstreamFactory.retryConfig(p));
        meters.ifAvailable(m -> TaggedRetryMetrics.ofRetryRegistry(r).bindTo(m));
        return r;
    }

    @Bean
    @ConditionalOnMissingBean
    public DownstreamFactory downstreamFactory(RestClient.Builder builder, HttpProperties p,
                                               CircuitBreakerRegistry cb, RetryRegistry retry) {
        return new DownstreamFactory(builder, p, cb, retry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "medigraph.ledger", name = "nodes")
    public LedgerClient ledgerClient(DownstreamFactory f, LedgerClientProperties p, ServiceTokenProvider tokens) {
        return new LedgerClient(p.nodes().stream()
                .map(url -> f.create("ledger@" + url.replaceFirst("^https?://", ""), url, TokenSource.service(tokens)))
                .toList());
    }
}
