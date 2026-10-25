package com.medigraph.beds;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for bed holds. Status changes are compare-and-set so confirm / release / expiry racing on the same hold
 * apply exactly once (and the bed counter is adjusted exactly once). Adapters: in-memory, Redis (TTL keys).
 */
public interface HoldStore {

    void save(BedHold hold);

    Optional<BedHold> find(String holdId);

    boolean transition(String holdId, BedHold.Status from, BedHold.Status to);

    List<BedHold> activeExpiredAt(Instant now);
}
