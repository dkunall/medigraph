package com.medigraph.ledger;

import com.medigraph.ledger.chain.Block;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.ledger.raft.RaftNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.medigraph.ledger.chain.LedgerFixtures.access;
import static com.medigraph.ledger.chain.LedgerFixtures.recordHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicatedLedgerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void sealCommitsABatchAsOneBlockReplicatedIdenticallyOnAllNodes() {
        ReplicatedLedger l = ReplicatedLedger.threeNode(1, 100, clock);
        List<LedgerEntry> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LedgerEntry e = recordHash("{r" + i + "}");
            batch.add(e);
            l.append(e);
        }
        Block b = l.seal().orElseThrow();
        assertThat(b.entries()).hasSize(10);
        assertThat(l.pendingCount()).isZero();
        l.cluster().tickUntil(() -> l.nodeStatus().stream().allMatch(s -> (int) s.get("blocks") == 1), 100);
        for (String id : List.of("ledger-1", "ledger-2", "ledger-3")) {
            assertThat(l.chainOf(id).tip().orElseThrow().hash()).isEqualTo(b.hash());
        }
        assertThat(l.verifyRecord(batch.get(4).entryId(), "{r4}".getBytes()).valid()).isTrue();
        assertThat(l.verifyRecord(batch.get(4).entryId(), "{r4-tampered}".getBytes()).valid()).isFalse();
    }

    @Test
    void autoSealsWhenBatchIsFull() {
        ReplicatedLedger l = ReplicatedLedger.threeNode(2, 3, clock);
        l.append(access("a"));
        l.append(access("b"));
        assertThat(l.chain().size()).isZero();
        l.append(access("c"));
        assertThat(l.chain().size()).isEqualTo(1);
        assertThat(l.seal()).isEmpty();
    }

    @Test
    void survivesLeaderFailureAndOldLeaderCatchesUp() {
        ReplicatedLedger l = ReplicatedLedger.threeNode(3, 100, clock);
        l.append(recordHash("{1}"));
        Block first = l.seal().orElseThrow();
        RaftNode<BlockProposal> leader = l.cluster().leader().orElseThrow();

        l.cluster().crash(leader.id());
        LedgerEntry e2 = recordHash("{2}");
        l.append(e2);
        Block second = l.seal().orElseThrow();
        assertThat(second.previousHash()).isEqualTo(first.hash());
        assertThat(l.cluster().leader().orElseThrow().id()).isNotEqualTo(leader.id());

        l.cluster().restart(leader.id());
        l.cluster().tickUntil(() -> l.chainOf(leader.id()).size() == 2, 300);
        assertThat(l.chainOf(leader.id()).tip().orElseThrow().hash()).isEqualTo(second.hash());
        assertThat(l.chainOf(leader.id()).verifyChain().valid()).isTrue();
    }

    @Test
    void withoutMajorityEntriesStayBufferedThenCommitAfterRecovery() {
        ReplicatedLedger l = ReplicatedLedger.threeNode(4, 100, clock);
        l.append(access("warmup"));
        l.seal();
        RaftNode<BlockProposal> leader = l.cluster().leader().orElseThrow();
        List<String> others = l.cluster().nodes().stream().map(RaftNode::id).filter(id -> !id.equals(leader.id())).toList();
        others.forEach(l.cluster()::crash);

        LedgerEntry e = access("during-outage");
        l.append(e);
        assertThatThrownBy(l::seal).isInstanceOf(LedgerUnavailableException.class);
        assertThat(l.isPending(e.entryId())).isTrue();

        others.forEach(l.cluster()::restart);
        Block b = l.seal().orElseThrow();
        assertThat(b.entries()).extracting(LedgerEntry::entryId).contains(e.entryId());
        // the retried proposal must not create duplicates even though the first attempt sat in the leader's log
        l.cluster().tickUntil(() -> false, 100);
        long copies = l.chain().blocks().stream().flatMap(x -> x.entries().stream())
                .filter(x -> x.entryId().equals(e.entryId())).count();
        assertThat(copies).isEqualTo(1);
        assertThat(l.verifyChain().valid()).isTrue();
    }

    @Test
    void requiresOddClusterOfAtLeastThree() {
        assertThatThrownBy(() -> new ReplicatedLedger(List.of("a", "b"), com.medigraph.ledger.raft.RaftConfig.defaults(), 1, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
