package com.medigraph.ledger.raft;

public class NotLeaderException extends RuntimeException {
    private final String leaderHint;

    public NotLeaderException(String nodeId, String leaderHint) {
        super(nodeId + " is not the leader" + (leaderHint == null ? "" : " (try " + leaderHint + ")"));
        this.leaderHint = leaderHint;
    }

    public String leaderHint() {
        return leaderHint;
    }
}
