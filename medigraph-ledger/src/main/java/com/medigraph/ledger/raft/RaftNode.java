package com.medigraph.ledger.raft;

import com.medigraph.ledger.raft.RaftMessage.AppendRequest;
import com.medigraph.ledger.raft.RaftMessage.AppendResponse;
import com.medigraph.ledger.raft.RaftMessage.VoteRequest;
import com.medigraph.ledger.raft.RaftMessage.VoteResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A simplified Raft node (Ongaro &amp; Ousterhout, 2014): leader election with randomized timeouts, log replication
 * with the prev-index/term consistency check, and majority commit restricted to current-term entries (the
 * "Figure 8" rule, which is why a new leader appends a no-op).
 *
 * <p>Simplifications, by design: no snapshots/log compaction, no membership changes, no pre-vote. Persistent state
 * ({@code currentTerm}, {@code votedFor}, log) is written through a {@link RaftStorage} before any reply leaves the
 * node; the in-memory log list is a read cache of it. On construction the node reloads that state, and resumes
 * applying after {@link StateMachine#lastAppliedIndex()}, so a process restart loses only volatile state.
 *
 * <p>Complexity: {@link #tick()} O(peers); handling an AppendRequest with k entries O(k) plus storage writes;
 * commit advancement O(peers) per index scanned (bounded by the entries acknowledged since the last commit);
 * conflict back-tracking skips a whole term per round trip. Space O(log length) (no compaction).
 *
 * <p>The node is a deterministic state machine driven from outside: {@link #tick()} advances logical time and
 * {@link #step(RaftMessage)} handles one message; outbound messages go to the supplied sink. Not thread-safe:
 * the owning {@link RaftCluster} serializes all calls.
 */
public final class RaftNode<C> {

    private final String id;
    private final List<String> peers;
    private final StateMachine<C> stateMachine;
    private final Consumer<RaftMessage> outbox;
    private final RaftConfig config;
    private final Random random;
    private final RaftStorage<C> storage;

    // persistent state (Figure 2)
    private long currentTerm;
    private String votedFor;
    private final List<LogEntry<C>> log = new ArrayList<>(); // index i lives at log.get(i - 1)

    // volatile state
    private Role role = Role.FOLLOWER;
    private String leaderId;
    private long commitIndex;
    private long lastApplied;
    private int electionElapsed;
    private int heartbeatElapsed;
    private int randomizedElectionTimeout;
    private final Set<String> votes = new HashSet<>();
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();

    public RaftNode(String id, List<String> peers, StateMachine<C> stateMachine, Consumer<RaftMessage> outbox,
                    RaftConfig config, Random random) {
        this(id, peers, stateMachine, outbox, config, random, new InMemoryRaftStorage<>());
    }

    /** Rebuilds the node from durable state: term, vote and log from storage; applied index from the state machine. */
    public RaftNode(String id, List<String> peers, StateMachine<C> stateMachine, Consumer<RaftMessage> outbox,
                    RaftConfig config, Random random, RaftStorage<C> storage) {
        this.id = id;
        this.peers = List.copyOf(peers);
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.config = config;
        this.random = random;
        this.storage = storage;
        RaftStorage.HardState hs = storage.loadHardState();
        this.currentTerm = hs.currentTerm();
        this.votedFor = hs.votedFor();
        this.log.addAll(storage.loadLog());
        // everything the state machine durably applied was committed when it was applied
        this.lastApplied = Math.min(stateMachine.lastAppliedIndex(), lastIndex());
        this.commitIndex = lastApplied;
        resetElectionTimer();
    }

    // ------------------------------------------------------------------ driving

    public void tick() {
        if (role == Role.LEADER) {
            if (++heartbeatElapsed >= config.heartbeatInterval()) {
                heartbeatElapsed = 0;
                broadcastAppend();
            }
            return;
        }
        if (++electionElapsed >= randomizedElectionTimeout) {
            startElection();
        }
    }

    public void step(RaftMessage m) {
        if (m.term() > currentTerm) {
            // any newer term demotes us; only AppendRequest tells us who the leader is
            becomeFollower(m.term(), m instanceof AppendRequest ? m.from() : null);
        }
        switch (m) {
            case VoteRequest r -> onVoteRequest(r);
            case VoteResponse r -> onVoteResponse(r);
            case AppendRequest r -> onAppendRequest(r);
            case AppendResponse r -> onAppendResponse(r);
        }
    }

    /**
     * Appends a client command to the leader's log and starts replicating it.
     *
     * @return the log index assigned; the entry is durable only once that index is committed in this term
     * @throws NotLeaderException if this node is not the leader
     */
    public long propose(C command) {
        if (role != Role.LEADER) {
            throw new NotLeaderException(id, leaderId);
        }
        LogEntry<C> e = new LogEntry<>(currentTerm, lastIndex() + 1, command);
        appendToLog(List.of(e));
        broadcastAppend();
        maybeAdvanceCommit(); // single-node cluster commits immediately
        return e.index();
    }

    /** Restart after a simulated crash: volatile leadership state is lost, persistent state is kept. */
    public void restart() {
        role = Role.FOLLOWER;
        leaderId = null;
        votes.clear();
        nextIndex.clear();
        matchIndex.clear();
        resetElectionTimer();
    }

    // ------------------------------------------------------------------ election

    private void startElection() {
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = id;
        persistHardState();
        leaderId = null;
        votes.clear();
        votes.add(id);
        resetElectionTimer();
        if (votes.size() > clusterSize() / 2) {
            becomeLeader();
            return;
        }
        for (String p : peers) {
            outbox.accept(new VoteRequest(id, p, currentTerm, lastIndex(), lastTerm()));
        }
    }

    private void onVoteRequest(VoteRequest r) {
        boolean grant = r.term() == currentTerm
                && (votedFor == null || votedFor.equals(r.from()))
                && candidateLogUpToDate(r.lastLogTerm(), r.lastLogIndex());
        if (grant) {
            votedFor = r.from();
            persistHardState(); // must be durable before the vote leaves this node
            resetElectionTimer(); // granting a vote counts as hearing from a would-be leader
        }
        outbox.accept(new VoteResponse(id, r.from(), currentTerm, grant));
    }

    /** Election restriction (5.4.1): only a candidate whose log is at least as up-to-date can win. */
    private boolean candidateLogUpToDate(long candLastTerm, long candLastIndex) {
        return candLastTerm > lastTerm() || (candLastTerm == lastTerm() && candLastIndex >= lastIndex());
    }

    private void onVoteResponse(VoteResponse r) {
        if (role != Role.CANDIDATE || r.term() != currentTerm || !r.granted()) {
            return;
        }
        votes.add(r.from());
        if (votes.size() > clusterSize() / 2) {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leaderId = id;
        heartbeatElapsed = 0;
        for (String p : peers) {
            nextIndex.put(p, lastIndex() + 1);
            matchIndex.put(p, 0L);
        }
        // no-op in the new term lets earlier-term entries commit safely (Figure 8)
        appendToLog(List.of(new LogEntry<>(currentTerm, lastIndex() + 1, null)));
        broadcastAppend();
        maybeAdvanceCommit();
    }

    private void becomeFollower(long term, String leader) {
        currentTerm = term;
        votedFor = null;
        persistHardState();
        role = Role.FOLLOWER;
        leaderId = leader;
        votes.clear();
        resetElectionTimer();
    }

    // ------------------------------------------------------------------ replication

    private void broadcastAppend() {
        for (String p : peers) {
            sendAppend(p);
        }
    }

    private void sendAppend(String peer) {
        long next = nextIndex.getOrDefault(peer, lastIndex() + 1);
        long prevIndex = next - 1;
        long end = Math.min(lastIndex(), prevIndex + config.maxEntriesPerAppend());
        List<LogEntry<C>> entries = prevIndex < end
                ? List.copyOf(log.subList((int) prevIndex, (int) end))
                : List.of();
        outbox.accept(new AppendRequest(id, peer, currentTerm, prevIndex, termAt(prevIndex), entries, commitIndex));
    }

    @SuppressWarnings("unchecked")
    private void onAppendRequest(AppendRequest r) {
        if (r.term() < currentTerm) {
            outbox.accept(new AppendResponse(id, r.from(), currentTerm, false, 0, 0));
            return;
        }
        // r.term == currentTerm: there is a legitimate leader for this term
        role = Role.FOLLOWER;
        leaderId = r.from();
        resetElectionTimer();

        if (r.prevLogIndex() > lastIndex()) {
            outbox.accept(new AppendResponse(id, r.from(), currentTerm, false, 0, lastIndex() + 1));
            return;
        }
        if (termAt(r.prevLogIndex()) != r.prevLogTerm()) {
            // back up over the whole conflicting term in one round trip
            long conflictTerm = termAt(r.prevLogIndex());
            long first = r.prevLogIndex();
            while (first > 1 && termAt(first - 1) == conflictTerm) {
                first--;
            }
            outbox.accept(new AppendResponse(id, r.from(), currentTerm, false, 0, Math.max(1, first)));
            return;
        }
        long index = r.prevLogIndex();
        List<LogEntry<C>> fresh = new ArrayList<>();
        for (LogEntry<?> raw : r.entries()) {
            LogEntry<C> e = (LogEntry<C>) raw;
            index = e.index();
            if (fresh.isEmpty() && index <= lastIndex()) {
                if (termAt(index) == e.term()) {
                    continue; // already have it (duplicate/reordered delivery)
                }
                if (index <= commitIndex) {
                    throw new IllegalStateException("Raft safety violated: leader overwrote committed index " + index);
                }
                truncateFrom(index);
            }
            fresh.add(e);
        }
        appendToLog(fresh); // durable before we acknowledge
        long lastNew = r.prevLogIndex() + r.entries().size();
        if (r.leaderCommit() > commitIndex) {
            commitIndex = Math.min(r.leaderCommit(), lastNew);
            applyCommitted();
        }
        outbox.accept(new AppendResponse(id, r.from(), currentTerm, true, lastNew, 0));
    }

    private void onAppendResponse(AppendResponse r) {
        if (role != Role.LEADER || r.term() != currentTerm) {
            return;
        }
        if (r.success()) {
            if (r.matchIndex() > matchIndex.getOrDefault(r.from(), 0L)) {
                matchIndex.put(r.from(), r.matchIndex());
            }
            nextIndex.put(r.from(), matchIndex.get(r.from()) + 1);
            maybeAdvanceCommit();
            if (nextIndex.get(r.from()) <= lastIndex()) {
                sendAppend(r.from()); // follower still behind: keep streaming
            }
        } else {
            long next = nextIndex.getOrDefault(r.from(), lastIndex() + 1);
            nextIndex.put(r.from(), Math.max(1, Math.min(next - 1, r.conflictIndex() > 0 ? r.conflictIndex() : next - 1)));
            sendAppend(r.from());
        }
    }

    private void maybeAdvanceCommit() {
        for (long n = lastIndex(); n > commitIndex; n--) {
            if (termAt(n) != currentTerm) {
                break; // never count replicas for older-term entries directly
            }
            int replicas = 1;
            for (String p : peers) {
                if (matchIndex.getOrDefault(p, 0L) >= n) {
                    replicas++;
                }
            }
            if (replicas > clusterSize() / 2) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry<C> e = log.get((int) lastApplied - 1);
            if (e.command() != null) {
                stateMachine.apply(e.index(), e.command());
            } else {
                stateMachine.markApplied(e.index());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void truncateFrom(long index) {
        storage.truncateFrom(index);
        while (lastIndex() >= index) {
            log.remove(log.size() - 1);
        }
    }

    private void appendToLog(List<LogEntry<C>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        storage.append(entries);
        log.addAll(entries);
    }

    private void persistHardState() {
        storage.saveHardState(new RaftStorage.HardState(currentTerm, votedFor));
    }

    private void resetElectionTimer() {
        electionElapsed = 0;
        randomizedElectionTimeout = config.electionTimeoutMin()
                + random.nextInt(config.electionTimeoutMax() - config.electionTimeoutMin());
    }

    private int clusterSize() {
        return peers.size() + 1;
    }

    long termAt(long index) {
        return index == 0 || index > lastIndex() ? 0 : log.get((int) index - 1).term();
    }

    public long lastIndex() {
        return log.size();
    }

    private long lastTerm() {
        return termAt(lastIndex());
    }

    // ------------------------------------------------------------------ introspection (tests, metrics, API)

    public String id() {
        return id;
    }

    public Role role() {
        return role;
    }

    public long currentTerm() {
        return currentTerm;
    }

    public String leaderId() {
        return leaderId;
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public long termOf(long index) {
        return termAt(index);
    }

    public List<LogEntry<C>> logView() {
        return List.copyOf(log);
    }
}
