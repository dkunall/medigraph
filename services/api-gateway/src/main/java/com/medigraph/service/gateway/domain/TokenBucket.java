package com.medigraph.service.gateway.domain;

/**
 * Classic token bucket: holds at most {@code capacity} tokens, refilled continuously at {@code refillPerSecond};
 * each request takes one. Allows bursts up to the capacity while bounding the long-run rate.
 *
 * <p>Refill is lazy: tokens are topped up from the elapsed time when a request arrives, so there is no timer
 * thread and an idle bucket costs nothing. {@link #tryConsume} is O(1) time, O(1) space; synchronized per
 * bucket, so contention only happens between requests of the same client.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;

    public TokenBucket(int capacity, double refillPerSecond, long nowNanos) {
        if (capacity < 1 || !(refillPerSecond > 0) || Double.isInfinite(refillPerSecond)) {
            throw new IllegalArgumentException("capacity >= 1 and refillPerSecond > 0 required");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** @return 0 when a token was taken, otherwise the nanoseconds until one becomes available. */
    public synchronized long tryConsume(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastNanos); // monotonic clock, but never trust a negative delta
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastNanos = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerNano);
    }

    public synchronized double available() {
        return tokens;
    }
}
