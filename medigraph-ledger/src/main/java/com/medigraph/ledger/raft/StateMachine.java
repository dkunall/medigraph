package com.medigraph.ledger.raft;

/** Applied exactly once per committed index, in index order, on every node. Must be deterministic. */
@FunctionalInterface
public interface StateMachine<C> {

    void apply(long index, C command);

    /**
     * Highest log index already applied, as recorded durably by the state machine itself (0 for a volatile one).
     * On restart the node resumes applying after it, so no committed entry is applied twice.
     */
    default long lastAppliedIndex() {
        return 0;
    }

    /** Called for every committed index, including the leader no-ops that carry no command. */
    default void markApplied(long index) {
    }
}
