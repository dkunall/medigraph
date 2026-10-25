package com.medigraph.service.gateway.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded least-recently-used map (a {@link LinkedHashMap} in access order with eldest-entry eviction): a hash
 * table threaded by a doubly-linked list, so get, put and eviction are all O(1) and memory is O(capacity).
 * The gateway keeps one {@link TokenBucket} per client in it, so a flood of distinct client keys cannot grow
 * memory without bound; the cost is that an evicted client's bucket restarts full. Thread-safe via one lock
 * (operations are O(1), so the critical section is tiny).
 */
public final class LruCache<K, V> {

    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity >= 1");
        }
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.capacity;
            }
        };
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> factory) {
        return map.computeIfAbsent(key, factory);
    }

    public synchronized int size() {
        return map.size();
    }
}
