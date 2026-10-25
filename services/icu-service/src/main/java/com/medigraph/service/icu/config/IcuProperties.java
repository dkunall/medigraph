package com.medigraph.service.icu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param processor          {@code kafka} (production: partitioned by patient, state in changelog topics) or
 *                           {@code in-memory} (one instance, state lost on restart; dev/tests)
 * @param watermarkInterval  how often wall-clock time closes silent windows; {@code 0s} disables the scheduler
 */
@ConfigurationProperties("medigraph.icu")
public record IcuProperties(@DefaultValue("http://localhost:8081") String identityUrl,
                            @DefaultValue("kafka") String processor,
                            @DefaultValue("localhost:9092") String kafkaBootstrap,
                            @DefaultValue("30s") Duration watermarkInterval) {
}
