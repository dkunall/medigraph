package com.medigraph.ledger.raft;

import com.medigraph.ledger.raft.RaftMessage.VoteRequest;
import com.medigraph.ledger.raft.RaftMessage.VoteResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RaftStorageRecoveryTest {

    /** A state machine that records its applied index durably alongside its data, as the JDBC block store does. */
    static final class DurableList implements StateMachine<String> {
        final List<String> applied = new ArrayList<>();
        long lastApplied;

        @Override
        public void apply(long index, String command) {
            applied.add(command);
            lastApplied = index;
        }

        @Override
        public void markApplied(long index) {
            lastApplied = index;
        }

        @Override
        public long lastAppliedIndex() {
            return lastApplied;
        }
    }

    private static RaftNode<String> node(InMemoryRaftStorage<String> storage, DurableList sm, List<RaftMessage> out) {
        return new RaftNode<>("n1", List.of(), sm, out::add, RaftConfig.defaults(), new Random(7), storage);
    }

    private static void electSelf(RaftNode<String> n) {
        for (int i = 0; i < 100 && n.role() != Role.LEADER; i++) {
            n.tick();
        }
        assertThat(n.role()).isEqualTo(Role.LEADER);
    }

    @Test
    void restartedNodeRecoversTermLogAndAppliedIndexWithoutReapplying() {
        InMemoryRaftStorage<String> storage = new InMemoryRaftStorage<>();
        DurableList sm = new DurableList();
        RaftNode<String> first = node(storage, sm, new ArrayList<>());
        electSelf(first);
        first.propose("a");
        first.propose("b");
        long term = first.currentTerm();
        long last = first.lastIndex();
        assertThat(sm.applied).containsExactly("a", "b");

        // process dies; a new node object is built from the same durable storage and state machine
        RaftNode<String> recovered = node(storage, sm, new ArrayList<>());
        assertThat(recovered.currentTerm()).isEqualTo(term);
        assertThat(recovered.lastIndex()).isEqualTo(last);
        assertThat(recovered.lastApplied()).isEqualTo(last);
        assertThat(recovered.role()).isEqualTo(Role.FOLLOWER);

        electSelf(recovered);
        assertThat(recovered.currentTerm()).isGreaterThan(term);
        recovered.propose("c");
        assertThat(sm.applied).containsExactly("a", "b", "c"); // nothing applied twice
    }

    @Test
    void voteSurvivesRestartSoANodeNeverVotesTwiceInOneTerm() {
        InMemoryRaftStorage<String> storage = new InMemoryRaftStorage<>();
        List<RaftMessage> out = new ArrayList<>();
        RaftNode<String> n = new RaftNode<>("n1", List.of("a", "b"), new DurableList(), out::add, RaftConfig.defaults(),
                new Random(1), storage);
        n.step(new VoteRequest("a", "n1", 5, 0, 0));
        assertThat(((VoteResponse) out.get(out.size() - 1)).granted()).isTrue();

        List<RaftMessage> out2 = new ArrayList<>();
        RaftNode<String> restarted = new RaftNode<>("n1", List.of("a", "b"), new DurableList(), out2::add,
                RaftConfig.defaults(), new Random(1), storage);
        restarted.step(new VoteRequest("b", "n1", 5, 0, 0));
        assertThat(((VoteResponse) out2.get(out2.size() - 1)).granted()).isFalse();
        assertThat(storage.loadHardState()).isEqualTo(new RaftStorage.HardState(5, "a"));
    }

    @Test
    void conflictingSuffixIsTruncatedInStorageToo() {
        InMemoryRaftStorage<String> storage = new InMemoryRaftStorage<>();
        storage.append(List.of(new LogEntry<>(1, 1, "x"), new LogEntry<>(1, 2, "stale")));
        storage.saveHardState(new RaftStorage.HardState(1, null));
        List<RaftMessage> out = new ArrayList<>();
        RaftNode<String> n = new RaftNode<>("n1", List.of("L", "o"), new DurableList(), out::add, RaftConfig.defaults(),
                new Random(3), storage);
        n.step(new RaftMessage.AppendRequest("L", "n1", 2, 1, 1, List.of(new LogEntry<>(2, 2, "fresh")), 0));
        assertThat(storage.loadLog()).extracting(LogEntry::command).containsExactly("x", "fresh");
        assertThat(n.logView()).isEqualTo(storage.loadLog());
    }
}
