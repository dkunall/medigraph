package com.medigraph.beds;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BedServiceTest {

    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-25T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    private final InMemoryBedRepository repo = new InMemoryBedRepository();
    private final InMemoryHoldStore holds = new InMemoryHoldStore();
    private final TestClock clock = new TestClock();
    private final BedService service = new BedService(repo, holds, clock);

    @Test
    void holdConfirmReleaseLifecycle() {
        repo.upsert(new BedInventory("H1", 2, 0, 0, 0));
        BedHold a = service.hold("H1", "p1", Duration.ofMinutes(15));
        BedHold b = service.hold("H1", "p2", Duration.ofMinutes(15));
        assertThat(repo.find("H1").orElseThrow().free()).isZero();
        assertThatThrownBy(() -> service.hold("H1", "p3", Duration.ofMinutes(15))).isInstanceOf(BedUnavailableException.class);

        service.confirm(a.holdId());
        assertThat(service.release(b.holdId())).isTrue();
        assertThat(service.release(b.holdId())).isFalse(); // idempotent
        BedInventory inv = repo.find("H1").orElseThrow();
        assertThat(inv.occupied()).isEqualTo(1);
        assertThat(inv.held()).isZero();
        assertThat(inv.free()).isEqualTo(1);
        assertThatThrownBy(() -> service.confirm(b.holdId())).isInstanceOf(BedUnavailableException.class);
    }

    @Test
    void holdsAutoReleaseAfterTtl() {
        repo.upsert(new BedInventory("H1", 1, 0, 0, 0));
        BedHold h = service.hold("H1", "p1", Duration.ofMinutes(10));
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(service.expireHolds()).isZero();
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(service.expireHolds()).isEqualTo(1);
        assertThat(repo.find("H1").orElseThrow().free()).isEqualTo(1);
        assertThat(holds.find(h.holdId()).orElseThrow().status()).isEqualTo(BedHold.Status.EXPIRED);
        assertThatThrownBy(() -> service.confirm(h.holdId())).isInstanceOf(BedUnavailableException.class);
        assertThat(service.expireHolds()).isZero();
    }

    @Test
    void confirmingAnExpiredHoldNeverAdmits() {
        repo.upsert(new BedInventory("H1", 1, 0, 0, 0));
        BedHold h = service.hold("H1", "p1", Duration.ofMinutes(10));
        clock.now = clock.now.plus(Duration.ofMinutes(11)); // reaper has not run yet
        assertThatThrownBy(() -> service.confirm(h.holdId())).hasMessageContaining("expired");
        assertThat(repo.find("H1").orElseThrow()).extracting(BedInventory::occupied, BedInventory::held).containsExactly(0, 0);
    }

    @Test
    void staleVersionWriteIsRejected() {
        repo.upsert(new BedInventory("H1", 5, 0, 0, 7));
        BedInventory read = repo.find("H1").orElseThrow();
        assertThat(repo.compareAndSet(read, read.withHeld(1))).isTrue();
        assertThat(repo.compareAndSet(read, read.withHeld(1))).isFalse(); // second writer with the same version
        assertThat(repo.find("H1").orElseThrow().version()).isEqualTo(8);
    }

    @RepeatedTest(5)
    void manyThreadsRaceForTheLastBedExactlyOneWins() throws Exception {
        repo.upsert(new BedInventory("H1", 20, 19, 0, 0)); // one bed left
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String patient = "p" + i;
            pool.submit(() -> {
                start.await();
                try {
                    service.hold("H1", patient, Duration.ofMinutes(15));
                    won.incrementAndGet();
                } catch (BedUnavailableException e) {
                    lost.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(threads - 1);
        assertThat(repo.find("H1").orElseThrow().free()).isZero();
    }

    @Test
    void mixedConcurrentHoldsReleasesAndConfirmsKeepCountersConsistent() throws Exception {
        repo.upsert(new BedInventory("H1", 25, 0, 0, 0));
        ExecutorService pool = Executors.newFixedThreadPool(32);
        ConcurrentLinkedQueue<BedHold> granted = new ConcurrentLinkedQueue<>();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            int n = i;
            pool.submit(() -> {
                start.await();
                try {
                    BedHold h = service.hold("H1", "p" + n, Duration.ofMinutes(15));
                    granted.add(h);
                    if (n % 3 == 0) service.release(h.holdId());
                    else if (n % 3 == 1) service.confirm(h.holdId());
                } catch (BedUnavailableException e) {
                    refused.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        BedInventory inv = repo.find("H1").orElseThrow();
        long confirmed = granted.stream().filter(h -> holds.find(h.holdId()).orElseThrow().status() == BedHold.Status.CONFIRMED).count();
        long active = granted.stream().filter(h -> holds.find(h.holdId()).orElseThrow().status() == BedHold.Status.ACTIVE).count();
        assertThat(inv.occupied()).isEqualTo((int) confirmed);
        assertThat(inv.held()).isEqualTo((int) active);
        assertThat(inv.occupied() + inv.held()).isLessThanOrEqualTo(25);
        assertThat(granted.size() + refused.get()).isEqualTo(400);
    }

    @Test
    void releaseRacingWithExpiryAdjustsCounterOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            repo.upsert(new BedInventory("H" + round, 1, 0, 0, 0));
            BedHold h = service.hold("H" + round, "p", Duration.ofMinutes(1));
            clock.now = clock.now.plus(Duration.ofMinutes(2));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            pool.submit(() -> { go.await(); return service.release(h.holdId()); });
            pool.submit(() -> { go.await(); return service.expireHolds(); });
            go.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            assertThat(repo.find("H" + round).orElseThrow().held()).isZero(); // never -1 (would throw), never 1
        }
    }
}
