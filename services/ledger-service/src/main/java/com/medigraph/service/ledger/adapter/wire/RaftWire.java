package com.medigraph.service.ledger.adapter.wire;

import com.medigraph.ledger.BlockProposal;
import com.medigraph.ledger.raft.LogEntry;
import com.medigraph.ledger.raft.RaftMessage;

import java.util.List;

/** JSON shape of a Raft RPC on {@code POST /internal/raft/messages} (shared by the inbound and outbound adapters). */
public record RaftWire(String type, String from, String to, long term,
                       long lastLogIndex, long lastLogTerm, boolean granted,
                       long prevLogIndex, long prevLogTerm, List<Entry> entries, long leaderCommit,
                       boolean success, long matchIndex, long conflictIndex) {

    public record Entry(long term, long index, BlockProposal command) {
    }

    public static RaftWire of(RaftMessage m) {
        return switch (m) {
            case RaftMessage.VoteRequest r ->
                    new RaftWire("VoteRequest", r.from(), r.to(), r.term(), r.lastLogIndex(), r.lastLogTerm(), false,
                            0, 0, List.of(), 0, false, 0, 0);
            case RaftMessage.VoteResponse r ->
                    new RaftWire("VoteResponse", r.from(), r.to(), r.term(), 0, 0, r.granted(), 0, 0, List.of(), 0, false, 0, 0);
            case RaftMessage.AppendRequest r -> new RaftWire("AppendRequest", r.from(), r.to(), r.term(), 0, 0, false,
                    r.prevLogIndex(), r.prevLogTerm(),
                    r.entries().stream().map(e -> new Entry(e.term(), e.index(), (BlockProposal) e.command())).toList(),
                    r.leaderCommit(), false, 0, 0);
            case RaftMessage.AppendResponse r -> new RaftWire("AppendResponse", r.from(), r.to(), r.term(), 0, 0, false,
                    0, 0, List.of(), 0, r.success(), r.matchIndex(), r.conflictIndex());
        };
    }

    public RaftMessage toMessage() {
        return switch (type) {
            case "VoteRequest" -> new RaftMessage.VoteRequest(from, to, term, lastLogIndex, lastLogTerm);
            case "VoteResponse" -> new RaftMessage.VoteResponse(from, to, term, granted);
            case "AppendRequest" -> new RaftMessage.AppendRequest(from, to, term, prevLogIndex, prevLogTerm,
                    entries == null ? List.of()
                            : entries.stream().map(e -> new LogEntry<>(e.term(), e.index(), e.command())).toList(),
                    leaderCommit);
            case "AppendResponse" -> new RaftMessage.AppendResponse(from, to, term, success, matchIndex, conflictIndex);
            default -> throw new IllegalArgumentException("unknown raft message type " + type);
        };
    }
}
