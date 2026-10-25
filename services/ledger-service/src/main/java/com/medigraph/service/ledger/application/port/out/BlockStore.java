package com.medigraph.service.ledger.application.port.out;

import com.medigraph.ledger.chain.Block;

import java.util.List;

/** Durable home of the replicated state machine: committed blocks plus the Raft index applied so far. */
public interface BlockStore {

    List<Block> loadBlocks();

    long lastApplied();

    /** Stores {@code block} and sets last_applied to {@code raftIndex} atomically (one transaction). */
    void appendBlock(Block block, long raftIndex);

    /** Advances last_applied for a log entry that produced no block (no-op or all-duplicate proposal). */
    void markApplied(long raftIndex);
}
