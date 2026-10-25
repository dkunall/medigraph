package com.medigraph.ledger.raft;

import java.util.List;

/** The two Raft RPCs and their replies, modelled as asynchronous messages. */
public sealed interface RaftMessage {

    String from();

    String to();

    long term();

    record VoteRequest(String from, String to, long term, long lastLogIndex, long lastLogTerm) implements RaftMessage {
    }

    record VoteResponse(String from, String to, long term, boolean granted) implements RaftMessage {
    }

    record AppendRequest(String from, String to, long term, long prevLogIndex, long prevLogTerm,
                         List<? extends LogEntry<?>> entries, long leaderCommit) implements RaftMessage {
    }

    /**
     * @param matchIndex    on success, the last index known to match the leader
     * @param conflictIndex on failure, where the leader should retry from (fast log back-tracking)
     */
    record AppendResponse(String from, String to, long term, boolean success, long matchIndex, long conflictIndex)
            implements RaftMessage {
    }
}
