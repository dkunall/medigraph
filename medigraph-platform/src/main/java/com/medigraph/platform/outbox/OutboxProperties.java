package com.medigraph.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code medigraph.outbox.*}. {@code eventsSink}: log | kafka. Retry backoff per row: min(2^attempts * base, max).
 */
@ConfigurationProperties("medigraph.outbox")
public record OutboxProperties(@DefaultValue("true") boolean relayEnabled, @DefaultValue("500ms") Duration pollInterval,
                               @DefaultValue("100") int batchSize, @DefaultValue("1s") Duration baseBackoff,
                               @DefaultValue("5m") Duration maxBackoff, @DefaultValue("log") String eventsSink,
                               @DefaultValue("medigraph.events") String eventsTopic,
                               @DefaultValue("localhost:9092") String kafkaBootstrapServers) {
}
