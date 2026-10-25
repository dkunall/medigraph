package com.medigraph.beds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Hold -> confirm (admit) or release / expire. Counter updates use optimistic concurrency: read the row, compute
 * the new value, CAS on version, retry on conflict with small jittered backoff. No double booking is possible
 * because a hold only succeeds when its CAS from a state with {@code free > 0} wins.
 */
public final class BedService {

    private static final int MAX_ATTEMPTS = 200;

    private final BedRepository beds;
    private final HoldStore holds;
    private final Clock clock;
    private final AtomicLong casConflicts = new AtomicLong();

    public BedService(BedRepository beds, HoldStore holds, Clock clock) {
        this.beds = beds;
        this.holds = holds;
        this.clock = clock;
    }

    public BedHold hold(String hospitalId, String patientId, Duration ttl) {
        update(hospitalId, inv -> {
            if (inv.free() <= 0) {
                throw new BedUnavailableException("no free ICU bed at " + hospitalId);
            }
            return inv.withHeld(+1);
        });
        Instant now = clock.instant();
        BedHold h = new BedHold(UUID.randomUUID().toString(), hospitalId, patientId, now, now.plus(ttl), BedHold.Status.ACTIVE);
        holds.save(h);
        return h;
    }

    /** Patient arrived: hold becomes an occupied bed. Fails if the hold already expired or was released. */
    public BedHold confirm(String holdId) {
        BedHold h = holds.find(holdId).orElseThrow(() -> new IllegalArgumentException("unknown hold " + holdId));
        if (h.status() == BedHold.Status.ACTIVE && !clock.instant().isBefore(h.expiresAt())) {
            expire(h);
            throw new BedUnavailableException("hold " + holdId + " expired");
        }
        if (!holds.transition(holdId, BedHold.Status.ACTIVE, BedHold.Status.CONFIRMED)) {
            throw new BedUnavailableException("hold " + holdId + " is no longer active");
        }
        update(h.hospitalId(), BedInventory::admitFromHold);
        return h.withStatus(BedHold.Status.CONFIRMED);
    }

    public boolean release(String holdId) {
        BedHold h = holds.find(holdId).orElseThrow(() -> new IllegalArgumentException("unknown hold " + holdId));
        if (!holds.transition(holdId, BedHold.Status.ACTIVE, BedHold.Status.RELEASED)) {
            return false;
        }
        update(h.hospitalId(), inv -> inv.withHeld(-1));
        return true;
    }

    /** TTL auto-release; called by a scheduler. Returns the number of holds expired. */
    public int expireHolds() {
        List<BedHold> due = holds.activeExpiredAt(clock.instant());
        int n = 0;
        for (BedHold h : due) {
            if (expire(h)) {
                n++;
            }
        }
        return n;
    }

    private boolean expire(BedHold h) {
        if (!holds.transition(h.holdId(), BedHold.Status.ACTIVE, BedHold.Status.EXPIRED)) {
            return false;
        }
        update(h.hospitalId(), inv -> inv.withHeld(-1));
        return true;
    }

    public void discharge(String hospitalId) {
        update(hospitalId, BedInventory::discharge);
    }

    private BedInventory update(String hospitalId, UnaryOperator<BedInventory> change) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            BedInventory cur = beds.find(hospitalId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown hospital " + hospitalId));
            BedInventory next = change.apply(cur);
            if (beds.compareAndSet(cur, next)) {
                return next;
            }
            casConflicts.incrementAndGet();
            if (attempt > 2) {
                try {
                    Thread.sleep(0, ThreadLocalRandom.current().nextInt(1, 50_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            } else {
                Thread.onSpinWait();
            }
        }
        throw new IllegalStateException("too much contention on " + hospitalId);
    }

    public long casConflicts() {
        return casConflicts.get();
    }
}
