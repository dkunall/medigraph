package com.medigraph.ledger.raft;

import java.util.List;

/**
 * Durable Raft state (Figure 2 "persistent state on all servers"): {@code currentTerm}, {@code votedFor} and the log.
 * {@link RaftNode} writes through this port <em>before</em> it answers any RPC, so a node that crashes and restarts
 * never votes twice in a term and never forgets an entry it acknowledged.
 *
 * <p>Implementations must make each call durable when it returns (fsync / committed transaction).
 * The in-memory implementation serves the deterministic simulation tests; the ledger service uses a JDBC one.
 */
public interface RaftStorage<C> {

    record HardState(long currentTerm, String votedFor) {
        public static final HardState INITIAL = new HardState(0, null);
    }

    HardState loadHardState();

    void saveHardState(HardState state);

    /** Entries in index order starting at index 1. */
    List<LogEntry<C>> loadLog();

    /** Appends entries whose indexes directly follow the current last index. */
    void append(List<LogEntry<C>> entries);

    /** Deletes the entry at {@code fromIndex} and every later one (conflict resolution). */
    void truncateFrom(long fromIndex);
}
