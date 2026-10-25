package com.medigraph.ledger.raft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Asynchronous in-memory message bus between Raft nodes with fault injection: crashed nodes, cut links
 * (partitions) and random message loss. Messages are delivered in FIFO order when the cluster drains the queue.
 */
public final class InProcessNetwork {

    private final Deque<RaftMessage> queue = new ArrayDeque<>();
    private final Set<String> down = new HashSet<>();
    private final Set<String> cutLinks = new HashSet<>();
    private final Random random;
    private double dropRate;
    private long delivered;
    private long dropped;

    public InProcessNetwork(Random random) {
        this.random = random;
    }

    public void send(RaftMessage m) {
        queue.addLast(m);
    }

    RaftMessage poll() {
        return queue.pollFirst();
    }

    boolean deliverable(RaftMessage m) {
        boolean ok = !down.contains(m.from()) && !down.contains(m.to())
                && !cutLinks.contains(link(m.from(), m.to()))
                && (dropRate == 0 || random.nextDouble() >= dropRate);
        if (ok) {
            delivered++;
        } else {
            dropped++;
        }
        return ok;
    }

    public void crash(String node) {
        down.add(node);
    }

    public void recover(String node) {
        down.remove(node);
    }

    public boolean isDown(String node) {
        return down.contains(node);
    }

    /** Cuts traffic in both directions between a and b. */
    public void cut(String a, String b) {
        cutLinks.add(link(a, b));
    }

    public void heal() {
        cutLinks.clear();
    }

    public void setDropRate(double dropRate) {
        this.dropRate = dropRate;
    }

    public long delivered() {
        return delivered;
    }

    public long dropped() {
        return dropped;
    }

    private static String link(String a, String b) {
        return a.compareTo(b) < 0 ? a + "<->" + b : b + "<->" + a;
    }
}
