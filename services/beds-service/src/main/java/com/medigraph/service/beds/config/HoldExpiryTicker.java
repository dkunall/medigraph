package com.medigraph.service.beds.config;

import com.medigraph.service.beds.application.BedAllocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Releases expired holds (TTL) so their beds return to the pool. Safe to run on every instance: CAS per hold. */
final class HoldExpiryTicker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryTicker.class);

    private final BedAllocation beds;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    HoldExpiryTicker(BedAllocation beds, Duration interval) {
        this.beds = beds;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (interval.isZero()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bed-hold-expiry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int n = beds.expireHolds();
                if (n > 0) {
                    log.info("released {} expired bed holds", n);
                }
            } catch (RuntimeException e) {
                log.error("hold expiry failed", e);
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
