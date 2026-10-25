package com.medigraph.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Default events sink for local runs and tests: logs ids only (payloads are PHI-free but noisy). */
public final class LoggingEventSink implements OutboxSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventSink.class);

    @Override
    public String destination() {
        return com.medigraph.common.port.OutboxMessage.EVENTS;
    }

    @Override
    public void deliver(List<OutboxRecord> batch) {
        batch.forEach(r -> log.info("event {} {}/{} (outbox #{})", r.eventType(), r.aggregateType(), r.aggregateId(), r.id()));
    }
}
