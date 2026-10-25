package com.medigraph.beds;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryHoldStore implements HoldStore {

    private final ConcurrentMap<String, BedHold> holds = new ConcurrentHashMap<>();

    @Override
    public void save(BedHold hold) {
        holds.put(hold.holdId(), hold);
    }

    @Override
    public Optional<BedHold> find(String holdId) {
        return Optional.ofNullable(holds.get(holdId));
    }

    @Override
    public boolean transition(String holdId, BedHold.Status from, BedHold.Status to) {
        boolean[] ok = {false};
        holds.computeIfPresent(holdId, (k, h) -> {
            if (h.status() != from) {
                return h;
            }
            ok[0] = true;
            return h.withStatus(to);
        });
        return ok[0];
    }

    @Override
    public List<BedHold> activeExpiredAt(Instant now) {
        return holds.values().stream()
                .filter(h -> h.status() == BedHold.Status.ACTIVE && !now.isBefore(h.expiresAt())).toList();
    }
}
