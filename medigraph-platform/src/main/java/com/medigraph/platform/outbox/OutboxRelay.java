package com.medigraph.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls due outbox rows with {@code FOR UPDATE SKIP LOCKED}, so several replicas of a service can relay in parallel
 * without double-claiming a row, groups them by destination and hands each group to its sink. Success stamps
 * {@code published_at}; failure bumps {@code attempts} and pushes {@code next_attempt_at} out exponentially.
 * The claim and the stamp are one transaction: a crash mid-delivery leaves the rows unpublished (re-sent later).
 *
 * <p>Cost per poll: one indexed range scan on {@code (next_attempt_at, id) WHERE published_at IS NULL}, O(batch log n).
 */
public final class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, OutboxSink> sinks;
    private final OutboxProperties props;
    private final Counter delivered;
    private final Counter failed;
    private ScheduledExecutorService timer;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, List<OutboxSink> sinks, OutboxProperties props,
                       MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sinks = new LinkedHashMap<>();
        sinks.forEach(s -> this.sinks.put(s.destination(), s));
        this.props = props;
        this.delivered = Counter.builder("medigraph.outbox.delivered").register(meters);
        this.failed = Counter.builder("medigraph.outbox.failed").register(meters);
    }

    /** One poll; returns how many rows were delivered. Public so tests can drain synchronously. */
    public int drainOnce() {
        Integer n = tx.execute(status -> {
            List<OutboxRecord> due = jdbc.query("SELECT id, destination, aggregate_type, aggregate_id, event_type, payload, attempts "
                            + "FROM outbox_message WHERE published_at IS NULL AND next_attempt_at <= now() "
                            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new OutboxRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7)), props.batchSize());
            Map<String, List<OutboxRecord>> byDest = new LinkedHashMap<>();
            due.forEach(r -> byDest.computeIfAbsent(r.destination(), k -> new ArrayList<>()).add(r));
            int ok = 0;
            for (var e : byDest.entrySet()) {
                OutboxSink sink = sinks.get(e.getKey());
                try {
                    if (sink == null) {
                        throw new IllegalStateException("no sink for destination " + e.getKey());
                    }
                    sink.deliver(e.getValue());
                    for (OutboxRecord r : e.getValue()) {
                        jdbc.update("UPDATE outbox_message SET published_at = now(), last_error = NULL WHERE id = ?", r.id());
                    }
                    ok += e.getValue().size();
                    delivered.increment(e.getValue().size());
                } catch (RuntimeException ex) {
                    failed.increment(e.getValue().size());
                    log.warn("outbox delivery to '{}' failed for {} rows: {}", e.getKey(), e.getValue().size(), ex.getMessage());
                    for (OutboxRecord r : e.getValue()) {
                        Duration wait = backoff(r.attempts() + 1);
                        jdbc.update("UPDATE outbox_message SET attempts = attempts + 1, last_error = ?, "
                                        + "next_attempt_at = now() + (? * interval '1 millisecond') WHERE id = ?",
                                truncate(ex.getMessage()), wait.toMillis(), r.id());
                    }
                }
            }
            return ok;
        });
        return n == null ? 0 : n;
    }

    /** Drains until nothing is due (tests / shutdown). */
    public int drainAll() {
        int total = 0;
        int n;
        while ((n = drainOnce()) > 0) {
            total += n;
        }
        return total;
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    Duration backoff(int attempts) {
        long base = props.baseBackoff().toMillis();
        long ms = attempts >= 20 ? Long.MAX_VALUE : base << Math.max(0, attempts - 1);
        return Duration.ofMillis(Math.min(ms, props.maxBackoff().toMillis()));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "error";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    @Override
    public synchronized void start() {
        if (!props.relayEnabled() || timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        long ms = props.pollInterval().toMillis();
        timer.scheduleWithFixedDelay(() -> {
            try {
                drainOnce();
            } catch (RuntimeException e) {
                log.warn("outbox poll failed: {}", e.getMessage());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
