package com.medigraph.service.ledger.config;

import com.medigraph.service.ledger.application.LedgerReplica;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Drives Raft's logical clock. Starts last and stops first, so the web server is up while the node participates. */
final class RaftTicker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RaftTicker.class);

    private final LedgerReplica replica;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    RaftTicker(LedgerReplica replica, Duration interval) {
        this.replica = replica;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-ticker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                replica.tick();
            } catch (RuntimeException e) {
                log.error("raft tick failed", e);
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

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // after the web server (which starts at MAX_VALUE - 1024 ... ) is up
    }
}
