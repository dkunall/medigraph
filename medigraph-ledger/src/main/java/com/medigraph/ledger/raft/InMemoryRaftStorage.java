package com.medigraph.ledger.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage that lives as long as the object: survives {@link RaftNode#restart()} in the simulation (the node object
 * is rebuilt from it), which is how the simulated "fsync" works. Not thread-safe; owned by one node.
 */
public final class InMemoryRaftStorage<C> implements RaftStorage<C> {

    private HardState hardState = HardState.INITIAL;
    private final List<LogEntry<C>> log = new ArrayList<>();

    @Override
    public HardState loadHardState() {
        return hardState;
    }

    @Override
    public void saveHardState(HardState state) {
        this.hardState = state;
    }

    @Override
    public List<LogEntry<C>> loadLog() {
        return List.copyOf(log);
    }

    @Override
    public void append(List<LogEntry<C>> entries) {
        for (LogEntry<C> e : entries) {
            if (e.index() != log.size() + 1) {
                throw new IllegalStateException("non-contiguous append at " + e.index() + ", last is " + log.size());
            }
            log.add(e);
        }
    }

    @Override
    public void truncateFrom(long fromIndex) {
        while (log.size() >= fromIndex) {
            log.remove(log.size() - 1);
        }
    }
}
