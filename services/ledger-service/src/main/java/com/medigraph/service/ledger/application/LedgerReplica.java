package com.medigraph.service.ledger.application;

import com.medigraph.ledger.BlockProposal;
import com.medigraph.ledger.chain.Block;
import com.medigraph.ledger.chain.Blockchain;
import com.medigraph.ledger.chain.InclusionProof;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.ledger.chain.VerificationResult;
import com.medigraph.ledger.raft.NotLeaderException;
import com.medigraph.ledger.raft.RaftConfig;
import com.medigraph.ledger.raft.RaftMessage;
import com.medigraph.ledger.raft.RaftNode;
import com.medigraph.ledger.raft.RaftStorage;
import com.medigraph.ledger.raft.Role;
import com.medigraph.ledger.raft.StateMachine;
import com.medigraph.service.ledger.application.port.out.BlockStore;
import com.medigraph.service.ledger.application.port.out.RaftTransport;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One ledger replica: a {@link RaftNode} whose state machine is the {@link Blockchain}, made durable through
 * {@link RaftStorage} (term, vote, log) and {@link BlockStore} (blocks + last applied index).
 *
 * <p>Threading: {@code RaftNode} is single-threaded by design, so every call into it (ticks, inbound RPCs,
 * proposals) happens under one lock. Outbound RPCs leave through a non-blocking {@link RaftTransport}.
 *
 * <p>Write path: {@link #submit} proposes a {@link BlockProposal} (timestamp fixed by the leader so every
 * replica builds a byte-identical block), then waits on a condition until the entry is applied locally, i.e.
 * committed by a majority and persisted. Only then does the client get its acknowledgement. If leadership changed
 * in between, the index may hold a different entry, so the term at that index is checked.
 *
 * <p>Idempotency: Apply skips entry ids already on the chain, so a client retry after a lost ack (or the
 * outbox re-delivering) never seals an entry twice.
 *
 * <p>Recovery: On construction, the chain is rebuilt from stored blocks (and fully re-verified), and Raft
 * resumes from the persisted term/vote/log with {@code commitIndex = lastApplied}; nothing is re-applied.
 *
 * <p>Fail-stop: If persisting an applied block fails, the replica stops serving (in-memory and durable state
 * could disagree) until restarted, at which point it reloads from the database and catches up from the leader.
 *
 * <p>Complexity: submit is O(k log k) for k entries (Merkle tree) plus one quorum round trip; inbound RPC
 * handling is O(entries in the message); proof lookup is O(1) index + O(log k) path.
 */
public final class LedgerReplica {

    public record SubmitResult(long logIndex, int submitted, int alreadySealed, long height) {
    }

    public record Status(String nodeId, String role, long term, String leaderId, long commitIndex, long lastApplied,
                         long lastLogIndex, long height, boolean chainValid, boolean healthy) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition appliedAdvanced = lock.newCondition();
    private final RaftNode<BlockProposal> node;
    private final Blockchain chain;
    private final BlockStore blocks;
    private final Clock clock;
    private long appliedIndex;
    private volatile String failure;

    public LedgerReplica(String nodeId, List<String> peers, RaftConfig config, RaftStorage<BlockProposal> storage,
                         BlockStore blocks, RaftTransport transport, Clock clock, Random random) {
        this.blocks = blocks;
        this.clock = clock;
        this.chain = Blockchain.restore(blocks.loadBlocks());
        this.appliedIndex = blocks.lastApplied();
        StateMachine<BlockProposal> machine = new StateMachine<>() {
            @Override
            public void apply(long index, BlockProposal proposal) {
                applyProposal(index, proposal);
            }

            @Override
            public long lastAppliedIndex() {
                return appliedIndex;
            }

            @Override
            public void markApplied(long index) {
                persist(() -> blocks.markApplied(index));
                advanceApplied(index);
            }
        };
        this.node = new RaftNode<>(nodeId, peers, machine, transport::send, config, random, storage);
    }

    // ------------------------------------------------------------------ Raft driving (scheduler / transport)

    public void tick() {
        withLock(() -> {
            if (failure == null) {
                node.tick();
            }
        });
    }

    public void receive(RaftMessage message) {
        withLock(() -> {
            ensureHealthy();
            node.step(message);
        });
    }

    // ------------------------------------------------------------------ client API

    /**
     * Replicates {@code entries} and returns once they are committed and durably applied on this (leader) node.
     *
     * @throws NotLeaderException      when this node is not the leader (carries a leader hint)
     * @throws CommitTimeoutException  when no quorum committed the proposal within {@code timeout}
     */
    public SubmitResult submit(List<LedgerEntry> entries, Duration timeout) throws InterruptedException {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("no entries");
        }
        lock.lockInterruptibly();
        try {
            ensureHealthy();
            List<LedgerEntry> fresh = dedupe(entries);
            if (fresh.isEmpty()) {
                requireLeader();
                return new SubmitResult(node.commitIndex(), 0, entries.size(), chain.size());
            }
            long term = node.currentTerm();
            long index = node.propose(new BlockProposal(fresh, clock.millis()));
            long remaining = timeout.toNanos();
            while (appliedIndex < index) {
                if (remaining <= 0) {
                    throw new CommitTimeoutException("entry " + index + " not committed within " + timeout);
                }
                remaining = appliedAdvanced.awaitNanos(remaining);
                ensureHealthy();
            }
            if (node.termOf(index) != term) {
                throw new CommitTimeoutException("leadership changed before index " + index + " committed; retry");
            }
            return new SubmitResult(index, fresh.size(), entries.size() - fresh.size(), chain.size());
        } finally {
            lock.unlock();
        }
    }

    /** Reads are served by the leader only, so a client never sees a lagging follower's chain. */
    public Optional<InclusionProof> proof(String entryId) {
        guardRead();
        return chain.proof(entryId);
    }

    public VerificationResult verifyContentHash(String entryId, String contentHashHex) {
        guardRead();
        return chain.verifyContentHash(entryId, contentHashHex);
    }

    public VerificationResult verifyChain() {
        guardRead();
        return chain.verifyChain();
    }

    public List<LedgerEntry> entriesFor(String subject) {
        guardRead();
        List<LedgerEntry> out = new ArrayList<>();
        for (Block b : chain.blocks()) {
            for (LedgerEntry e : b.entries()) {
                if (e.subject().equals(subject)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    public Optional<Block> block(long height) {
        guardRead();
        return height >= 0 && height < chain.size() ? Optional.of(chain.block(height)) : Optional.empty();
    }

    /** Local view, served by any node (operators and tests use it to watch replication). */
    public Status status() {
        lock.lock();
        try {
            return new Status(node.id(), node.role().name(), node.currentTerm(), node.leaderId(), node.commitIndex(),
                    appliedIndex, node.lastIndex(), chain.size(), chain.verifyChain().valid(), failure == null);
        } finally {
            lock.unlock();
        }
    }

    public boolean healthy() {
        return failure == null;
    }

    public String failureReason() {
        return failure;
    }

    // ------------------------------------------------------------------ state machine

    private void applyProposal(long index, BlockProposal proposal) {
        List<LedgerEntry> fresh = dedupe(proposal.entries());
        if (fresh.isEmpty()) {
            persist(() -> blocks.markApplied(index));
        } else {
            Block block = chain.append(proposal.proposedAtMillis(), fresh);
            persist(() -> blocks.appendBlock(block, index));
        }
        advanceApplied(index);
    }

    private List<LedgerEntry> dedupe(List<LedgerEntry> entries) {
        Map<String, LedgerEntry> fresh = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            if (chain.entry(e.entryId()).isEmpty()) {
                fresh.putIfAbsent(e.entryId(), e);
            }
        }
        return List.copyOf(fresh.values());
    }

    private void persist(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            failure = "failed to persist applied state: " + e.getMessage();
            throw new ReplicaFailedException(failure);
        }
    }

    private void advanceApplied(long index) {
        appliedIndex = index;
        appliedAdvanced.signalAll();
    }

    // ------------------------------------------------------------------ helpers

    private void guardRead() {
        withLock(() -> {
            ensureHealthy();
            requireLeader();
        });
    }

    private void requireLeader() {
        if (node.role() != Role.LEADER) {
            throw new NotLeaderException(node.id(), node.leaderId());
        }
    }

    private void ensureHealthy() {
        if (failure != null) {
            throw new ReplicaFailedException(failure);
        }
    }

    private void withLock(Runnable r) {
        lock.lock();
        try {
            r.run();
        } finally {
            lock.unlock();
        }
    }

    /** For tests: block until this node has applied at least {@code index}. */
    public boolean awaitApplied(long index, long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (appliedIndex < index && remaining > 0) {
                remaining = appliedAdvanced.awaitNanos(remaining);
            }
            return appliedIndex >= index;
        } finally {
            lock.unlock();
        }
    }
}
