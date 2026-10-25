package com.medigraph.service.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * {@code medigraph.ledger.raft.*}. Timers are in ticks of {@code tickInterval}: with the defaults (10 ms tick) the
 * leader heartbeats every 50 ms and followers start an election after 150-300 ms without one (randomised).
 * {@code peers} maps every <i>other</i> node id to its base URL; empty means a single-node cluster.
 */
@ConfigurationProperties("medigraph.ledger.raft")
public record LedgerNodeProperties(@DefaultValue("ledger1") String nodeId,
                                   @DefaultValue Map<String, String> peers,
                                   @DefaultValue("10ms") Duration tickInterval,
                                   @DefaultValue("15") int electionTicksMin,
                                   @DefaultValue("30") int electionTicksMax,
                                   @DefaultValue("5") int heartbeatTicks,
                                   @DefaultValue("64") int maxEntriesPerAppend,
                                   @DefaultValue("5s") Duration commitTimeout,
                                   @DefaultValue("500ms") Duration rpcTimeout) {

    public LedgerNodeProperties {
        peers = peers == null ? Map.of() : Map.copyOf(peers);
        if (peers.containsKey(nodeId)) {
            throw new IllegalArgumentException("medigraph.ledger.raft.peers must not contain this node (" + nodeId + ")");
        }
    }
}
