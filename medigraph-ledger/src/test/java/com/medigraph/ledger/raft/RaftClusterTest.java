package com.medigraph.ledger.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaftClusterTest {

    private final Map<String, List<String>> applied = new HashMap<>();

    private RaftCluster<String> cluster(long seed) {
        List<String> ids = List.of("n1", "n2", "n3");
        ids.forEach(id -> applied.put(id, new ArrayList<>()));
        return new RaftCluster<>(ids, id -> (index, cmd) -> applied.get(id).add(cmd), RaftConfig.defaults(), seed);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 1234})
    void electsExactlyOneLeaderWithinBoundedTime(long seed) {
        RaftCluster<String> c = cluster(seed);
        assertThat(c.awaitLeader(200)).isPresent();
        long leaders = c.nodes().stream().filter(n -> n.role() == Role.LEADER).count();
        assertThat(leaders).isEqualTo(1);
        long term = c.leader().orElseThrow().currentTerm();
        c.tickUntil(() -> false, 50); // heartbeats keep the leader in place
        assertThat(c.leader().orElseThrow().currentTerm()).isEqualTo(term);
    }

    @Test
    void committedEntriesAreAppliedInOrderOnAllNodes() {
        RaftCluster<String> c = cluster(7);
        c.awaitLeader(200);
        List<RaftCluster.Proposal> ps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ps.add(c.submit("cmd-" + i));
        }
        assertThat(c.awaitOutcome(ps.get(19), 100)).isEqualTo(RaftCluster.Outcome.COMMITTED);
        c.tickUntil(() -> applied.values().stream().allMatch(l -> l.size() == 20), 50);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expected.add("cmd-" + i);
        }
        applied.values().forEach(l -> assertThat(l).containsExactlyElementsOf(expected));
    }

    @Test
    void leaderFailureElectsNewLeaderInHigherTermAndClusterKeepsCommitting() {
        RaftCluster<String> c = cluster(11);
        RaftNode<String> old = c.awaitLeader(200).orElseThrow();
        RaftCluster.Proposal before = c.submit("before-crash");
        assertThat(c.awaitOutcome(before, 100)).isEqualTo(RaftCluster.Outcome.COMMITTED);

        c.crash(old.id());
        RaftNode<String> fresh = c.awaitLeader(300).orElseThrow();
        assertThat(fresh.id()).isNotEqualTo(old.id());
        assertThat(fresh.currentTerm()).isGreaterThan(old.currentTerm());

        RaftCluster.Proposal after = c.submit("after-crash");
        assertThat(c.awaitOutcome(after, 100)).isEqualTo(RaftCluster.Outcome.COMMITTED); // 2 of 3 is a majority

        // old leader comes back, steps down, and catches up
        c.restart(old.id());
        c.tickUntil(() -> applied.get(old.id()).contains("after-crash"), 300);
        assertThat(old.role()).isEqualTo(Role.FOLLOWER);
        assertThat(applied.get(old.id())).containsExactly("before-crash", "after-crash");
    }

    @Test
    void partitionedOldLeaderCannotCommitAndItsEntryIsOverwritten() {
        RaftCluster<String> c = cluster(5);
        RaftNode<String> old = c.awaitLeader(200).orElseThrow();
        c.partition(old.id());
        RaftCluster.Proposal doomed = c.submitVia(old.id(), "minority-write");

        // the isolated node still believes it leads (old term) until it hears a higher term
        c.tickUntil(() -> c.leader().filter(n -> !n.id().equals(old.id())).isPresent(), 300);
        RaftNode<String> fresh = c.leader().orElseThrow();
        assertThat(old.role()).isEqualTo(Role.LEADER);
        assertThat(fresh.id()).isNotEqualTo(old.id());
        RaftCluster.Proposal winner = c.submit("majority-write");
        assertThat(c.awaitOutcome(winner, 100)).isEqualTo(RaftCluster.Outcome.COMMITTED);
        assertThat(old.commitIndex()).isLessThan(doomed.index());

        c.heal();
        c.tickUntil(() -> applied.get(old.id()).contains("majority-write"), 300);
        assertThat(c.outcome(doomed)).isEqualTo(RaftCluster.Outcome.LOST);
        applied.values().forEach(l -> assertThat(l).doesNotContain("minority-write"));
        assertThat(old.logView()).isEqualTo(fresh.logView()); // logs converge (Log Matching)
    }

    @Test
    void noMajorityMeansNoCommit() {
        RaftCluster<String> c = cluster(9);
        RaftNode<String> leader = c.awaitLeader(200).orElseThrow();
        c.nodes().stream().filter(n -> n != leader).forEach(n -> c.crash(n.id()));
        RaftCluster.Proposal p = c.submitVia(leader.id(), "alone");
        assertThat(c.awaitOutcome(p, 200)).isEqualTo(RaftCluster.Outcome.PENDING);
    }

    @Test
    void followersRejectProposals() {
        RaftCluster<String> c = cluster(3);
        RaftNode<String> leader = c.awaitLeader(200).orElseThrow();
        RaftNode<String> follower = c.nodes().stream().filter(n -> n != leader).findFirst().orElseThrow();
        assertThatThrownBy(() -> follower.propose("x")).isInstanceOf(NotLeaderException.class)
                .hasMessageContaining(leader.id());
    }

    @Test
    void safetyHoldsUnderMessageLossAndRepeatedLeaderCrashes() {
        RaftCluster<String> c = cluster(2024);
        c.setDropRate(0.10);
        List<RaftCluster.Proposal> committed = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            RaftNode<String> l = c.awaitLeader(500).orElseThrow();
            RaftCluster.Proposal p = c.submit("r" + round);
            if (c.awaitOutcome(p, 300) == RaftCluster.Outcome.COMMITTED) {
                committed.add(p);
            }
            c.crash(l.id());
            c.tickUntil(() -> false, 30);
            c.restart(l.id());
        }
        c.setDropRate(0);
        c.tickUntil(() -> false, 300);
        assertThat(committed).isNotEmpty();
        // State Machine Safety: every node applied the same sequence (prefix-consistent), containing all commits
        List<String> reference = applied.values().stream().max((a, b) -> a.size() - b.size()).orElseThrow();
        for (List<String> l : applied.values()) {
            assertThat(reference.subList(0, l.size())).isEqualTo(l);
        }
        for (RaftCluster.Proposal p : committed) {
            assertThat(c.outcome(p)).isEqualTo(RaftCluster.Outcome.COMMITTED);
        }
        // Election Safety: at most one leader per term
        Map<Long, String> leaderPerTerm = new HashMap<>();
        for (RaftNode<String> n : c.nodes()) {
            if (n.role() == Role.LEADER) {
                assertThat(leaderPerTerm.putIfAbsent(n.currentTerm(), n.id())).isNull();
            }
        }
    }

    @Test
    void configValidation() {
        assertThatThrownBy(() -> new RaftConfig(4, 8, 2, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
