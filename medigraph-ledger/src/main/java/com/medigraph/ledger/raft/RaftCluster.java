package com.medigraph.ledger.raft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Runs N {@link RaftNode}s in-process over an {@link InProcessNetwork}. All mutation goes through this class's
 * monitor, so the nodes themselves need no locking. Time is logical: {@link #tick()} advances every live node by one
 * tick and then drains the message queue. A wall-clock driver can call {@code tick()} on a schedule; tests call it
 * directly, which makes elections and failures fully reproducible from the seed.
 */
public final class RaftCluster<C> {

    /** A proposal is identified by (index, term): if a different term ever commits at that index, it was lost. */
    public record Proposal(long index, long term, String leader) {
    }

    public enum Outcome { COMMITTED, LOST, PENDING }

    private static final int MAX_MESSAGES_PER_TICK = 100_000;

    private final Map<String, RaftNode<C>> nodes = new LinkedHashMap<>();
    private final InProcessNetwork network;
    private long ticks;

    public RaftCluster(List<String> ids, Function<String, StateMachine<C>> stateMachines, RaftConfig config, long seed) {
        Random seedSource = new Random(seed);
        this.network = new InProcessNetwork(new Random(seedSource.nextLong()));
        for (String id : ids) {
            List<String> peers = new ArrayList<>(ids);
            peers.remove(id);
            nodes.put(id, new RaftNode<>(id, peers, stateMachines.apply(id), network::send, config,
                    new Random(seedSource.nextLong())));
        }
    }

    public synchronized void tick() {
        ticks++;
        for (RaftNode<C> n : nodes.values()) {
            if (!network.isDown(n.id())) {
                n.tick();
            }
        }
        drain();
    }

    private void drain() {
        int processed = 0;
        RaftMessage m;
        while ((m = network.poll()) != null) {
            if (++processed > MAX_MESSAGES_PER_TICK) {
                throw new IllegalStateException("message storm: more than " + MAX_MESSAGES_PER_TICK + " in one tick");
            }
            if (network.deliverable(m)) {
                nodes.get(m.to()).step(m);
            }
        }
    }

    /** Ticks until {@code condition} holds; returns false if it did not within {@code maxTicks}. */
    public synchronized boolean tickUntil(BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            tick();
        }
        return condition.getAsBoolean();
    }

    /**
     * The live leader with the highest term (a deposed leader that is partitioned away may still believe it leads
     * in an older term; it can never commit, so callers should not treat it as the leader).
     */
    public synchronized Optional<RaftNode<C>> leader() {
        RaftNode<C> best = null;
        for (RaftNode<C> n : nodes.values()) {
            if (!network.isDown(n.id()) && n.role() == Role.LEADER && (best == null || n.currentTerm() > best.currentTerm())) {
                best = n;
            }
        }
        return Optional.ofNullable(best);
    }

    public synchronized Optional<RaftNode<C>> awaitLeader(int maxTicks) {
        tickUntil(() -> leader().isPresent(), maxTicks);
        return leader();
    }

    public synchronized Proposal submit(C command) {
        RaftNode<C> l = leader().orElseThrow(() -> new NotLeaderException("cluster", null));
        long index = l.propose(command);
        drain();
        return new Proposal(index, l.currentTerm(), l.id());
    }

    /** Proposes through a specific node (used to show that a partitioned old leader cannot commit). */
    public synchronized Proposal submitVia(String nodeId, C command) {
        RaftNode<C> n = node(nodeId);
        long index = n.propose(command);
        drain();
        return new Proposal(index, n.currentTerm(), nodeId);
    }

    public synchronized Outcome outcome(Proposal p) {
        for (RaftNode<C> n : nodes.values()) {
            if (n.commitIndex() >= p.index()) {
                return n.termOf(p.index()) == p.term() ? Outcome.COMMITTED : Outcome.LOST;
            }
        }
        return Outcome.PENDING;
    }

    public synchronized Outcome awaitOutcome(Proposal p, int maxTicks) {
        tickUntil(() -> outcome(p) != Outcome.PENDING, maxTicks);
        return outcome(p);
    }

    public synchronized void crash(String id) {
        network.crash(id);
    }

    public synchronized void restart(String id) {
        network.recover(id);
        node(id).restart();
    }

    public synchronized void partition(String isolated) {
        for (String other : nodes.keySet()) {
            if (!other.equals(isolated)) {
                network.cut(isolated, other);
            }
        }
    }

    public synchronized void heal() {
        network.heal();
    }

    public synchronized void setDropRate(double rate) {
        network.setDropRate(rate);
    }

    public synchronized RaftNode<C> node(String id) {
        RaftNode<C> n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("unknown node " + id);
        }
        return n;
    }

    public synchronized List<RaftNode<C>> nodes() {
        return List.copyOf(nodes.values());
    }

    public synchronized long ticks() {
        return ticks;
    }

    public synchronized InProcessNetwork network() {
        return network;
    }
}
