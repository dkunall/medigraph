package com.medigraph.platform.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes events to Kafka keyed by aggregate id (per-aggregate ordering within a partition), with an idempotent
 * producer and {@code acks=all}; headers carry the outbox id and event type so consumers can de-duplicate.
 * The batch is acknowledged only after every send is confirmed.
 */
public final class KafkaEventSink implements OutboxSink, AutoCloseable {

    private final Producer<String, String> producer;
    private final String topic;

    public KafkaEventSink(Producer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    public static KafkaEventSink create(String bootstrapServers, String topic) {
        return new KafkaEventSink(new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)), topic);
    }

    @Override
    public String destination() {
        return com.medigraph.common.port.OutboxMessage.EVENTS;
    }

    @Override
    public void deliver(List<OutboxRecord> batch) {
        List<Future<?>> acks = new ArrayList<>();
        for (OutboxRecord r : batch) {
            ProducerRecord<String, String> rec = new ProducerRecord<>(topic, r.aggregateId(), r.payload());
            rec.headers().add(new RecordHeader("outbox-id", Long.toString(r.id()).getBytes(StandardCharsets.UTF_8)));
            rec.headers().add(new RecordHeader("event-type", r.eventType().getBytes(StandardCharsets.UTF_8)));
            acks.add(producer.send(rec));
        }
        try {
            for (Future<?> f : acks) {
                f.get(15, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("kafka publish failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
