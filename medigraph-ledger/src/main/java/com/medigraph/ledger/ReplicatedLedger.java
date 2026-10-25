package com.medigraph.ledger;

import com.medigraph.ledger.chain.Block;
import com.medigraph.ledger.chain.Blockchain;
import com.medigraph.ledger.chain.InclusionProof;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.ledger.chain.VerificationResult;
import com.medigraph.ledger.raft.RaftCluster;
import com.medigraph.ledger.raft.RaftConfig;
import com.medigraph.ledger.raft.RaftNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Permissioned integrity ledger: a batching front-end over a Raft-replicated hash chain.
 *
 * <p>{@link #append} buffers an entry; {@link #seal} (or a full batch) proposes the buffer as one block through Raft
 * and returns once a majority has committed it. Each node applies committed proposals to its own {@link Blockchain}.
 * The apply step skips entry ids it has already sealed, so a retried proposal (leader died after replicating but
 * before we saw the commit) never produces duplicates: sealing is idempotent.
 */
public final class ReplicatedLedger {

    private final RaftCluster<BlockProposal> cluster;
    private final Map<String, Blockchain> chains = new HashMap<>();
    private final List<LedgerEntry> pending = new ArrayList<>();
    private final int batchSize;
    private final int maxTicksPerSeal;
    private final Clock clock;

    public ReplicatedLedger(List<String> nodeIds, RaftConfig config, long seed, int batchSize, Clock clock) {
        if (nodeIds.size() < 3 || nodeIds.size() % 2 == 0) {
            throw new IllegalArgumentException("use an odd number (>= 3) of ledger nodes");
        }
        for (String id : nodeIds) {
            chains.put(id, new Blockchain());
        }
        this.cluster = new RaftCluster<>(nodeIds, id -> (index, p) -> applyTo(chains.get(id), p), config, seed);
        this.batchSize = batchSize;
        this.maxTicksPerSeal = config.electionTimeoutMax() * 20;
        this.clock = clock;
    }

    public static ReplicatedLedger threeNode(long seed, int batchSize, Clock clock) {
        return new ReplicatedLedger(List.of("ledger-1", "ledger-2", "ledger-3"), RaftConfig.defaults(), seed, batchSize, clock);
    }

    private static void applyTo(Blockchain chain, BlockProposal p) {
        List<LedgerEntry> fresh = p.entries().stream().filter(e -> chain.entry(e.entryId()).isEmpty()).toList();
        if (!fresh.isEmpty()) {
            chain.append(p.proposedAtMillis(), fresh);
        }
    }

    /** Buffers an entry; seals automatically when the batch is full. */
    public synchronized void append(LedgerEntry entry) {
        pending.add(entry);
        if (pending.size() >= batchSize) {
            seal();
        }
    }

    /**
     * Commits all buffered entries as one block via Raft.
     *
     * @return the sealed block, or empty when nothing was pending
     * @throws LedgerUnavailableException if no majority is reachable; entries stay buffered for the next attempt
     */
    public synchronized Optional<Block> seal() {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        BlockProposal proposal = new BlockProposal(pending, clock.millis());
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cluster.awaitLeader(maxTicksPerSeal).isEmpty()) {
                break;
            }
            RaftCluster.Proposal p = cluster.submit(proposal);
            RaftCluster.Outcome outcome = cluster.awaitOutcome(p, maxTicksPerSeal);
            if (outcome == RaftCluster.Outcome.COMMITTED) {
                String first = proposal.entries().get(0).entryId();
                pending.clear();
                Blockchain chain = chains.get(p.leader());
                // commit means applied on the leader; the block holding our first entry is the one we sealed
                return chain.proof(first).map(ip -> chain.block(ip.blockHeight()));
            }
            // PENDING: no majority behind that leader (it may be a stale, partitioned one) -> retry, a majority side
            // elects a newer leader meanwhile. LOST: a new leader overwrote the slot -> retry.
            // Retrying is safe either way because apply is idempotent by entry id.
        }
        throw new LedgerUnavailableException("ledger could not reach a majority; " + pending.size() + " entries buffered");
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized boolean isPending(String entryId) {
        return pending.stream().anyMatch(e -> e.entryId().equals(entryId));
    }

    /** Chain of the current leader (or of any live node if leaderless); reads are served by the leader. */
    public synchronized Blockchain chain() {
        return cluster.leader().map(n -> chains.get(n.id())).orElseGet(() -> chains.values().iterator().next());
    }

    public synchronized Blockchain chainOf(String nodeId) {
        return chains.get(nodeId);
    }

    public synchronized Optional<InclusionProof> proof(String entryId) {
        return chain().proof(entryId);
    }

    public synchronized Optional<LedgerEntry> entry(String entryId) {
        return chain().entry(entryId);
    }

    public synchronized VerificationResult verifyRecord(String entryId, byte[] content) {
        return chain().verifyRecord(entryId, content);
    }

    public synchronized VerificationResult verifyChain() {
        return chain().verifyChain();
    }

    public synchronized List<Map<String, Object>> nodeStatus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RaftNode<BlockProposal> n : cluster.nodes()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", n.id());
            m.put("role", n.role().name());
            m.put("term", n.currentTerm());
            m.put("commitIndex", n.commitIndex());
            m.put("down", cluster.network().isDown(n.id()));
            m.put("blocks", chains.get(n.id()).size());
            out.add(m);
        }
        return out;
    }

    /** Exposed for fault-injection tests and the demo endpoints. */
    public RaftCluster<BlockProposal> cluster() {
        return cluster;
    }
}
