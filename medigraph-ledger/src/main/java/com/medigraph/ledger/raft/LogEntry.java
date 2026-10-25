package com.medigraph.ledger.raft;

/** A Raft log slot. {@code command == null} is the leader's no-op entry appended on election. */
public record LogEntry<C>(long term, long index, C command) {
}
