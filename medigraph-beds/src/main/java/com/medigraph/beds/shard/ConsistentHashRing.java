package com.medigraph.beds.shard;

import com.medigraph.common.crypto.Sha256;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hashing with virtual nodes, used to assign hospitals to bed-inventory shards. Adding or removing a
 * shard moves only ~1/N of the keys. Lookup is O(log(N*V)) via {@link TreeMap#ceilingEntry}.
 */
public final class ConsistentHashRing<N> {

    private final int virtualNodes;
    private final NavigableMap<Long, N> ring = new TreeMap<>();
    private final Set<N> nodes = new LinkedHashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing(int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes >= 1");
        }
        this.virtualNodes = virtualNodes;
    }

    static long hash(String s) {
        return ByteBuffer.wrap(Sha256.digest(s.getBytes(StandardCharsets.UTF_8))).getLong();
    }

    public void addNode(N node) {
        lock.writeLock().lock();
        try {
            if (!nodes.add(node)) {
                return;
            }
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(hash(node + "#" + i), node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeNode(N node) {
        lock.writeLock().lock();
        try {
            if (!nodes.remove(node)) {
                return;
            }
            for (int i = 0; i < virtualNodes; i++) {
                ring.remove(hash(node + "#" + i), node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public N nodeFor(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("ring is empty");
            }
            Map.Entry<Long, N> e = ring.ceilingEntry(hash(key));
            return e != null ? e.getValue() : ring.firstEntry().getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** First {@code count} distinct nodes clockwise from the key: primary plus replicas. */
    public List<N> nodesFor(String key, int count) {
        lock.readLock().lock();
        try {
            List<N> out = new ArrayList<>();
            if (ring.isEmpty()) {
                return out;
            }
            int want = Math.min(count, nodes.size());
            long h = hash(key);
            for (N n : ring.tailMap(h, true).values()) {
                if (out.size() == want) break;
                if (!out.contains(n)) out.add(n);
            }
            for (N n : ring.headMap(h, false).values()) {
                if (out.size() == want) break;
                if (!out.contains(n)) out.add(n);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<N> nodes() {
        lock.readLock().lock();
        try {
            return Set.copyOf(nodes);
        } finally {
            lock.readLock().unlock();
        }
    }
}
