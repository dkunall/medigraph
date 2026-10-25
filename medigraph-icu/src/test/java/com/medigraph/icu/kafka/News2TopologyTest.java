package com.medigraph.icu.kafka;

import com.medigraph.icu.RiskLevel;
import com.medigraph.icu.VitalType;
import com.medigraph.icu.stream.InMemoryVitalsProcessor;
import com.medigraph.icu.stream.StreamConfig;
import com.medigraph.icu.stream.VitalReading;
import com.medigraph.icu.stream.Vitals;
import com.medigraph.icu.stream.WindowScore;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real Kafka Streams topology without a broker via TopologyTestDriver. */
class News2TopologyTest {

    private static final long MIN = 60_000;
    private static final long T0 = 1_800_000_000_000L - (1_800_000_000_000L % (5 * MIN));

    private TopologyTestDriver driver;
    private TestInputTopic<String, VitalReading> in;
    private TestOutputTopic<String, WindowScore> scores;
    private TestOutputTopic<String, WindowScore> alerts;

    @BeforeEach
    void setUp() {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "news2-test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(News2Topology.build("vitals", "news2-scores", "news2-alerts",
                StreamConfig.defaults(), false), p);
        in = driver.createInputTopic("vitals", new StringSerializer(), new JsonSerde<>(VitalReading.class).serializer());
        scores = driver.createOutputTopic("news2-scores", new StringDeserializer(), new JsonSerde<>(WindowScore.class).deserializer());
        alerts = driver.createOutputTopic("news2-alerts", new StringDeserializer(), new JsonSerde<>(WindowScore.class).deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void pipe(List<VitalReading> rs) {
        rs.forEach(r -> in.pipeInput(r.patientId(), r, r.eventTimeMillis()));
    }

    @Test
    void highRiskPatientProducesAlertOnAlertsTopic() {
        pipe(Vitals.high("p1", T0 + MIN));
        pipe(Vitals.healthy("p2", T0 + MIN));
        pipe(List.of(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 125, T0 + 8 * MIN)));
        pipe(List.of(VitalReading.of(Vitals.id(), "p2", VitalType.PULSE, 70, T0 + 8 * MIN)));
        List<WindowScore> s = scores.readValuesToList();
        assertThat(s).hasSize(2);
        List<WindowScore> a = alerts.readValuesToList();
        assertThat(a).singleElement().satisfies(w -> {
            assertThat(w.patientId()).isEqualTo("p1");
            assertThat(w.score().risk()).isEqualTo(RiskLevel.HIGH);
            assertThat(w.score().total()).isEqualTo(14);
        });
    }

    @Test
    void stateSurvivesInTheStoreAndCooldownApplies() {
        for (int w = 0; w < 4; w++) pipe(Vitals.high("p1", T0 + w * 5 * MIN + MIN));
        pipe(List.of(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 125, T0 + 30 * MIN)));
        assertThat(scores.readValuesToList()).hasSize(4);
        assertThat(alerts.readValuesToList()).extracting(WindowScore::alertReason).containsExactly("NEW");
    }

    @Test
    void streamTimePunctuationClosesSilentPatientsWindow() {
        pipe(Vitals.high("silent", T0 + MIN));
        // only another patient's data advances stream time past the silent patient's window + lateness
        for (int m = 2; m <= 10; m++) {
            pipe(List.of(VitalReading.of(Vitals.id(), "other", VitalType.PULSE, 70, T0 + m * MIN)));
        }
        assertThat(alerts.readValuesToList()).extracting(WindowScore::patientId).contains("silent");
    }

    @Test
    void lateRecordsAreDroppedLikeTheInMemoryProcessor() {
        pipe(Vitals.healthy("p1", T0 + MIN));
        pipe(List.of(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 70, T0 + 8 * MIN)));
        pipe(List.of(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 140, T0 + 2 * MIN))); // late
        pipe(List.of(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 70, T0 + 20 * MIN)));
        List<WindowScore> s = scores.readValuesToList();
        assertThat(s).hasSize(2);
        assertThat(s.get(0).score().components()).containsEntry(VitalType.PULSE, 0);
    }

    @Test
    void kafkaTopologyAndInMemoryProcessorAgree() {
        List<VitalReading> all = new ArrayList<>();
        for (int w = 0; w < 12; w++) {
            long t = T0 + w * 5 * MIN + MIN;
            all.addAll(w % 4 == 0 ? Vitals.high("p1", t) : w % 4 == 1 ? Vitals.medium("p1", t) : Vitals.healthy("p1", t));
        }
        all.add(VitalReading.of(Vitals.id(), "p1", VitalType.PULSE, 70, T0 + 70 * MIN));
        pipe(all);
        InMemoryVitalsProcessor mem = new InMemoryVitalsProcessor(StreamConfig.defaults());
        List<WindowScore> memOut = new ArrayList<>();
        mem.subscribe(memOut::add);
        all.forEach(mem::submit);
        assertThat(scores.readValuesToList()).isEqualTo(memOut);
    }
}
