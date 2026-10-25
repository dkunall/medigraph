package com.medigraph.icu.kafka;

import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalType;
import com.medigraph.icu.stream.StreamConfig;
import com.medigraph.icu.stream.VitalReading;
import com.medigraph.icu.stream.Vitals;
import com.medigraph.icu.stream.WindowScore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real broker (Testcontainers): the exactly-once Kafka Streams topology scores a closed NEWS2 window end to end,
 * producer to scores topic to relay listener. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaStreamsVitalsProcessorContainerTest {

    private static final long MIN = 60_000;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Test
    void highRiskWindowIsScoredThroughTheBroker() throws Exception {
        // no topics are pre-created: start() has to create them, as it does against a fresh broker in compose
        List<WindowScore> seen = new CopyOnWriteArrayList<>();
        long t0 = System.currentTimeMillis() / (5 * MIN) * (5 * MIN) - 60 * MIN;
        try (KafkaStreamsVitalsProcessor p = new KafkaStreamsVitalsProcessor(KAFKA.getBootstrapServers(), "news2-it",
                StreamConfig.defaults())) {
            p.subscribe(seen::add);
            p.start();
            Vitals.high("p1", t0 + MIN).forEach(p::submit);
            // a later reading advances stream time past window end + allowed lateness
            p.submit(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 125, t0 + 8 * MIN));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
        }
        assertThat(seen).first().satisfies(w -> {
            assertThat(w.patientId()).isEqualTo("p1");
            assertThat(w.score().risk()).isEqualTo(RiskLevel.HIGH);
        });
    }
}
