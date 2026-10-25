package com.medigraph.icu.kafka;

import com.medigraph.icu.stream.StreamConfig;
import com.medigraph.icu.stream.VitalReading;
import com.medigraph.icu.stream.VitalsStreamProcessor;
import com.medigraph.icu.stream.WindowScore;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Real-broker adapter: produces readings to Kafka, runs the {@link News2Topology}, and relays the scores topic to
 * listeners. Requires a broker (docker-compose); not exercised by {@code mvn test}, which covers the topology with
 * TopologyTestDriver instead.
 */
public final class KafkaStreamsVitalsProcessor implements VitalsStreamProcessor {

    private static final List<String> TOPICS = List.of("vitals", "news2-scores", "news2-alerts");
    private static final int PARTITIONS = 3;

    private final String bootstrapServers;
    private final KafkaProducer<String, VitalReading> producer;
    private final KafkaStreams streams;
    private final String inputTopic;
    private final List<Consumer<WindowScore>> listeners = new CopyOnWriteArrayList<>();
    private final Thread relay;
    private volatile boolean running = true;

    public KafkaStreamsVitalsProcessor(String bootstrapServers, String applicationId, StreamConfig cfg) {
        this.bootstrapServers = bootstrapServers;
        this.inputTopic = TOPICS.get(0);
        Properties sp = new Properties();
        sp.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        sp.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        sp.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        this.streams = new KafkaStreams(News2Topology.build(inputTopic, "news2-scores", "news2-alerts", cfg, true), sp);

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(pp, new StringSerializer(), new JsonSerde<>(VitalReading.class).serializer());

        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, applicationId + "-relay");
        cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        KafkaConsumer<String, WindowScore> consumer = new KafkaConsumer<>(cp, new StringDeserializer(),
                new JsonSerde<>(WindowScore.class).deserializer());
        this.relay = Thread.ofVirtual().unstarted(() -> {
            try (consumer) {
                consumer.subscribe(List.of("news2-scores"));
                while (running) {
                    consumer.poll(Duration.ofMillis(500)).forEach(r -> listeners.forEach(l -> l.accept(r.value())));
                }
            }
        });
    }

    public void start() {
        ensureTopics();
        streams.start();
        relay.start();
    }

    /**
     * Kafka Streams shuts the whole client down if a source topic is missing when it first rebalances, and broker
     * auto-creation only happens on the first produce. So the topics are created up front; existing ones are left alone.
     */
    private void ensureTopics() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            for (String topic : TOPICS) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException("could not create topic " + topic, e.getCause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating topics", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("broker at " + bootstrapServers + " did not answer within 30s", e);
        }
    }

    @Override
    public boolean healthy() {
        return streams.state().isRunningOrRebalancing() || streams.state() == KafkaStreams.State.CREATED;
    }

    @Override
    public void submit(VitalReading reading) {
        producer.send(new ProducerRecord<>(inputTopic, null, reading.eventTimeMillis(), reading.patientId(), reading));
    }

    @Override
    public void advanceTime(long nowMillis) {
        // Stream-time punctuation inside the topology closes silent windows; nothing to do client-side.
    }

    @Override
    public void subscribe(Consumer<WindowScore> listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        running = false;
        producer.close();
        streams.close(Duration.ofSeconds(10));
    }
}
