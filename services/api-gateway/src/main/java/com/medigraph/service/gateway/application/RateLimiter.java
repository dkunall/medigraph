package com.medigraph.service.gateway.application;

import com.medigraph.service.gateway.domain.LruCache;
import com.medigraph.service.gateway.domain.TokenBucket;

import java.util.function.LongSupplier;

/**
 * Per-client token buckets kept in a bounded {@link LruCache}: O(1) per request, O(maxClients) memory.
 * Limits are per gateway instance; with N replicas a client gets up to N times the rate (a shared Redis
 * bucket would fix that and is listed as a limitation).
 */
public final class RateLimiter {

    /** Result of a check; {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;
    private final LruCache<String, TokenBucket> buckets;

    public RateLimiter(int capacity, double refillPerSecond, int maxClients, LongSupplier nanoClock) {
        new TokenBucket(capacity, refillPerSecond, 0); // validate eagerly
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
        this.buckets = new LruCache<>(maxClients);
    }

    public Decision check(String clientKey) {
        long now = nanoClock.getAsLong();
        TokenBucket b = buckets.computeIfAbsent(clientKey, k -> new TokenBucket(capacity, refillPerSecond, now));
        long waitNanos = b.tryConsume(now);
        return waitNanos == 0 ? new Decision(true, 0)
                : new Decision(false, Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L));
    }

    public int trackedClients() {
        return buckets.size();
    }
}
