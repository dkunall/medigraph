package com.medigraph.platform.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffTest {

    private final OutboxRelay relay = new OutboxRelay(null, null, List.of(),
            new OutboxProperties(false, Duration.ofMillis(500), 100, Duration.ofSeconds(1), Duration.ofMinutes(5),
                    "log", "t", "localhost:9092"), new SimpleMeterRegistry());

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(relay.backoff(9)).isEqualTo(Duration.ofSeconds(256));
        assertThat(relay.backoff(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(relay.backoff(64)).isEqualTo(Duration.ofMinutes(5)); // no shift overflow
    }
}
