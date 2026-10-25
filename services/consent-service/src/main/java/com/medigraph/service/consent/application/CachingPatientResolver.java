package com.medigraph.service.consent.application;

import com.medigraph.service.consent.application.port.out.PatientResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU + TTL cache in front of identity-mpi-service. One access decision resolves the same patient up to three
 * times (canonicalise, consents, break-glass grants); this keeps that to one remote call.
 * <p>
 * LRU via an access-ordered {@link LinkedHashMap}: get/put O(1), eviction of the eldest entry O(1), memory
 * O(maxEntries). Staleness bound: an MPI merge becomes visible here within {@code ttl}. Only successful lookups are
 * cached, so an unknown ID is never pinned as "not found".
 */
public final class CachingPatientResolver implements PatientResolver {

    private record Cached(ResolvedPatient value, Instant expiresAt) {
    }

    private final PatientResolver delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> lru;

    public CachingPatientResolver(PatientResolver delegate, Duration ttl, int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
        this.lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public ResolvedPatient resolve(String healthIdText) {
        Instant now = clock.instant();
        synchronized (lru) {
            Cached c = lru.get(healthIdText);
            if (c != null && now.isBefore(c.expiresAt())) {
                return c.value();
            }
        }
        ResolvedPatient fresh = delegate.resolve(healthIdText); // outside the lock: never block others on I/O
        if (!ttl.isZero()) {
            synchronized (lru) {
                lru.put(healthIdText, new Cached(fresh, now.plus(ttl)));
            }
        }
        return fresh;
    }
}
