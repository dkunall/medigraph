package com.medigraph.service.icu.config;

import com.medigraph.service.icu.application.VitalsIngestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Advances the event-time watermark from the wall clock, so a patient whose monitor went silent still scores. */
final class WatermarkTicker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WatermarkTicker.class);

    private final VitalsIngestion ingestion;
    private final Duration interval;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    WatermarkTicker(VitalsIngestion ingestion, Duration interval, Clock clock) {
        this.ingestion = ingestion;
        this.interval = interval;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (interval.isZero()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "news2-watermark");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ingestion.advanceWatermark(clock.millis());
            } catch (RuntimeException e) {
                log.error("watermark advance failed", e);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
