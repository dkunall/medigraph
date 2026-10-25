package com.medigraph.ledger.raft;

/**
 * Timing in logical ticks (the cluster driver decides how long a tick is). Election timeouts are drawn uniformly
 * from [{@code electionTimeoutMin}, {@code electionTimeoutMax}) per node and per reset, which makes split votes
 * unlikely to repeat. Heartbeats must be much shorter than the minimum election timeout.
 */
public record RaftConfig(int electionTimeoutMin, int electionTimeoutMax, int heartbeatInterval, int maxEntriesPerAppend) {

    public RaftConfig {
        if (electionTimeoutMin <= heartbeatInterval * 2 || electionTimeoutMax <= electionTimeoutMin) {
            throw new IllegalArgumentException("need heartbeat*2 < electionMin < electionMax");
        }
    }

    public static RaftConfig defaults() {
        return new RaftConfig(10, 20, 2, 64);
    }
}
