package com.medigraph.service.ledger.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.ledger.BlockProposal;
import com.medigraph.ledger.raft.LogEntry;
import com.medigraph.ledger.raft.RaftStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Raft persistent state in PostgreSQL. Every method commits before returning (synchronous_commit is on by
 * default, so a commit is fsync'd), which is what Raft needs: a node must not grant a vote or acknowledge an
 * append it could forget after a crash.
 */
public final class JdbcRaftStorage implements RaftStorage<BlockProposal> {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerJson json;

    public JdbcRaftStorage(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = new LedgerJson(mapper);
    }

    @Override
    public HardState loadHardState() {
        List<HardState> rows = jdbc.query("SELECT current_term, voted_for FROM raft_hard_state WHERE id = 1",
                (rs, i) -> new HardState(rs.getLong(1), rs.getString(2)));
        return rows.isEmpty() ? HardState.INITIAL : rows.get(0);
    }

    @Override
    public void saveHardState(HardState s) {
        jdbc.update("INSERT INTO raft_hard_state (id, current_term, voted_for) VALUES (1, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET current_term = EXCLUDED.current_term, voted_for = EXCLUDED.voted_for",
                s.currentTerm(), s.votedFor());
    }

    @Override
    public List<LogEntry<BlockProposal>> loadLog() {
        return jdbc.query("SELECT term, log_index, command FROM raft_log ORDER BY log_index",
                (rs, i) -> new LogEntry<>(rs.getLong(1), rs.getLong(2), json.proposal(rs.getString(3))));
    }

    @Override
    public void append(List<LogEntry<BlockProposal>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate("INSERT INTO raft_log (log_index, term, command) VALUES (?, ?, ?)",
                entries, entries.size(), (ps, e) -> {
                    ps.setLong(1, e.index());
                    ps.setLong(2, e.term());
                    ps.setString(3, e.command() == null ? null : json.write(e.command()));
                }));
    }

    @Override
    public void truncateFrom(long fromIndex) {
        jdbc.update("DELETE FROM raft_log WHERE log_index >= ?", fromIndex);
    }
}
