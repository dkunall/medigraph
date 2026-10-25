package com.medigraph.beds.shard;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistentHashRingTest {

    private static final int KEYS = 50_000;

    private static ConsistentHashRing<String> ring(int shards, int vnodes) {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(vnodes);
        for (int i = 0; i < shards; i++) r.addNode("shard-" + i);
        return r;
    }

    @Test
    void virtualNodesGiveEvenDistribution() {
        ConsistentHashRing<String> r = ring(5, 200);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < KEYS; i++) counts.merge(r.nodeFor("hospital-" + i), 1, Integer::sum);
        double mean = KEYS / 5.0;
        counts.values().forEach(c -> assertThat(Math.abs(c - mean) / mean).isLessThan(0.15));
    }

    @Test
    void addingAShardMovesRoughlyOneOverNKeysAndOnlyToTheNewShard() {
        ConsistentHashRing<String> r = ring(4, 200);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < KEYS; i++) before.put("hospital-" + i, r.nodeFor("hospital-" + i));
        r.addNode("shard-4");
        int moved = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = r.nodeFor(e.getKey());
            if (!now.equals(e.getValue())) {
                moved++;
                assertThat(now).isEqualTo("shard-4");
            }
        }
        double fraction = moved / (double) KEYS;
        assertThat(fraction).isBetween(0.12, 0.28); // ideal 1/5 = 0.20
    }

    @Test
    void removingAShardOnlyMovesItsKeys() {
        ConsistentHashRing<String> r = ring(5, 100);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 10_000; i++) before.put("k" + i, r.nodeFor("k" + i));
        r.removeNode("shard-2");
        before.forEach((k, v) -> {
            if (!v.equals("shard-2")) assertThat(r.nodeFor(k)).isEqualTo(v);
            else assertThat(r.nodeFor(k)).isNotEqualTo("shard-2");
        });
    }

    @Test
    void replicasAreDistinctAndStartWithPrimary() {
        ConsistentHashRing<String> r = ring(3, 50);
        List<String> rep = r.nodesFor("hospital-42", 5);
        assertThat(rep).hasSize(3).doesNotHaveDuplicates();
        assertThat(rep.get(0)).isEqualTo(r.nodeFor("hospital-42"));
    }

    @Test
    void emptyRingFailsLoudly() {
        assertThatThrownBy(() -> new ConsistentHashRing<String>(10).nodeFor("x")).isInstanceOf(IllegalStateException.class);
    }
}
