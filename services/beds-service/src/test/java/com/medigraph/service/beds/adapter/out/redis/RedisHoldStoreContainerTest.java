package com.medigraph.service.beds.adapter.out.redis;

import com.medigraph.beds.BedHold;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Redis (Testcontainers): the Lua compare-and-set transition is atomic under concurrency and the
 * active-expiry sorted set returns only expired ACTIVE holds. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisHoldStoreContainerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static RedisHoldStore store;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(factory);
        t.afterPropertiesSet();
        store = new RedisHoldStore(t);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private static BedHold hold(Instant expires) {
        return new BedHold(UUID.randomUUID().toString(), "H1", "12345678901234", Instant.now(), expires, BedHold.Status.ACTIVE);
    }

    @Test
    void casTransitionHasExactlyOneWinner() throws Exception {
        BedHold h = hold(Instant.now().plusSeconds(600));
        store.save(h);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            BedHold.Status to = i % 2 == 0 ? BedHold.Status.CONFIRMED : BedHold.Status.RELEASED;
            fs.add(pool.submit(() -> {
                go.await();
                return store.transition(h.holdId(), BedHold.Status.ACTIVE, to);
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> f : fs) {
            wins += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(store.find(h.holdId()).orElseThrow().status()).isNotEqualTo(BedHold.Status.ACTIVE);
    }

    @Test
    void expiryIndexReturnsOnlyExpiredActiveHolds() {
        Instant now = Instant.now();
        BedHold expired = hold(now.minusSeconds(5));
        BedHold live = hold(now.plusSeconds(600));
        BedHold confirmed = hold(now.minusSeconds(5));
        store.save(expired);
        store.save(live);
        store.save(confirmed);
        store.transition(confirmed.holdId(), BedHold.Status.ACTIVE, BedHold.Status.CONFIRMED);
        assertThat(store.activeExpiredAt(now)).extracting(BedHold::holdId)
                .contains(expired.holdId()).doesNotContain(live.holdId(), confirmed.holdId());
    }
}
