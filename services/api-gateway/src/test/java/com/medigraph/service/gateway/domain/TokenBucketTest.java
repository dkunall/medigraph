package com.medigraph.service.gateway.domain;

import com.medigraph.service.gateway.application.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void burstThenSteadyRefill() {
        TokenBucket b = new TokenBucket(3, 2.0, 0);
        assertThat(b.tryConsume(0)).isZero();
        assertThat(b.tryConsume(0)).isZero();
        assertThat(b.tryConsume(0)).isZero();
        assertThat(b.tryConsume(0)).isEqualTo(SECOND / 2); // empty: next token in 0.5 s at 2/s
        assertThat(b.tryConsume(SECOND / 2)).isZero();
        assertThat(b.tryConsume(100 * SECOND)).isZero();
        assertThat(b.available()).isEqualTo(2.0); // refill is capped at capacity
    }

    @Test
    void clockGoingBackwardsDoesNotMintTokens() {
        TokenBucket b = new TokenBucket(1, 1.0, 10 * SECOND);
        assertThat(b.tryConsume(10 * SECOND)).isZero();
        assertThat(b.tryConsume(5 * SECOND)).isPositive();
    }

    @Test
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new TokenBucket(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limiterIsPerClientAndBoundedByLru() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(2, 1.0, 3, now::get);
        assertThat(limiter.check("a").allowed()).isTrue();
        assertThat(limiter.check("a").allowed()).isTrue();
        RateLimiter.Decision denied = limiter.check("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
        assertThat(limiter.check("b").allowed()).isTrue(); // independent bucket
        for (int i = 0; i < 100; i++) {
            limiter.check("flood-" + i);
        }
        assertThat(limiter.trackedClients()).isEqualTo(3); // memory bounded
        now.addAndGet(SECOND);
        assertThat(limiter.check("a").allowed()).isTrue();
    }

    @Test
    void lruEvictsLeastRecentlyUsed() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.computeIfAbsent("a", k -> 1);
        c.computeIfAbsent("b", k -> 2);
        c.computeIfAbsent("a", k -> 99); // touch a
        c.computeIfAbsent("c", k -> 3);  // evicts b
        assertThat(c.computeIfAbsent("a", k -> 99)).isEqualTo(1);
        assertThat(c.computeIfAbsent("b", k -> 42)).isEqualTo(42);
    }
}
