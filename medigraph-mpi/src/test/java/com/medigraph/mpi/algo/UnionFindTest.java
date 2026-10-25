package com.medigraph.mpi.algo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnionFindTest {

    @Test
    void unionIsTransitive() {
        UnionFind<String> uf = new UnionFind<>();
        for (String s : new String[]{"a", "b", "c", "d"}) {
            uf.add(s);
        }
        uf.union("a", "b");
        uf.union("c", "b");
        assertThat(uf.connected("a", "c")).isTrue();
        assertThat(uf.connected("a", "d")).isFalse();
        assertThat(uf.componentCount()).isEqualTo(2);
        assertThat(uf.members("c")).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void repeatedUnionIsIdempotent() {
        UnionFind<Integer> uf = new UnionFind<>();
        uf.add(1);
        uf.add(2);
        Integer r1 = uf.union(1, 2);
        Integer r2 = uf.union(2, 1);
        assertThat(r1).isEqualTo(r2);
        assertThat(uf.componentCount()).isEqualTo(1);
        uf.add(1);
        assertThat(uf.size()).isEqualTo(2);
    }

    @Test
    void unknownElementFails() {
        assertThatThrownBy(() -> new UnionFind<String>().find("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agreesWithNaiveLabelPropagationOnRandomGraph() {
        int n = 2_000;
        UnionFind<Integer> uf = new UnionFind<>();
        int[] label = new int[n];
        for (int i = 0; i < n; i++) {
            uf.add(i);
            label[i] = i;
        }
        Random r = new Random(3);
        for (int e = 0; e < 1_500; e++) {
            int a = r.nextInt(n);
            int b = r.nextInt(n);
            uf.union(a, b);
            int la = label[a];
            int lb = label[b];
            if (la != lb) {
                for (int i = 0; i < n; i++) {
                    if (label[i] == lb) {
                        label[i] = la;
                    }
                }
            }
        }
        for (int t = 0; t < 5_000; t++) {
            int a = r.nextInt(n);
            int b = r.nextInt(n);
            assertThat(uf.connected(a, b)).isEqualTo(label[a] == label[b]);
        }
        assertThat(uf.components()).hasSize(uf.componentCount());
    }
}
