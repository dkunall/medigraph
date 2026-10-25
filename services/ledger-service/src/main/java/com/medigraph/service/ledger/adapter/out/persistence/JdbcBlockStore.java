package com.medigraph.service.ledger.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.ledger.chain.Block;
import com.medigraph.service.ledger.application.port.out.BlockStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

public final class JdbcBlockStore implements BlockStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerJson json;

    public JdbcBlockStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = new LedgerJson(mapper);
    }

    @Override
    public List<Block> loadBlocks() {
        return jdbc.query("SELECT height, previous_hash, merkle_root, timestamp_millis, entries, hash FROM ledger_block "
                        + "ORDER BY height",
                (rs, i) -> new Block(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        json.entries(rs.getString(5)), rs.getString(6)));
    }

    @Override
    public long lastApplied() {
        Long v = jdbc.queryForObject("SELECT last_applied FROM ledger_state WHERE id = 1", Long.class);
        return v == null ? 0 : v;
    }

    @Override
    public void appendBlock(Block b, long raftIndex) {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO ledger_block (height, previous_hash, merkle_root, timestamp_millis, hash, entries, raft_index) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", b.height(), b.previousHash(), b.merkleRoot(), b.timestampMillis(),
                    b.hash(), json.write(b.entries()), raftIndex);
            jdbc.update("UPDATE ledger_state SET last_applied = ? WHERE id = 1", raftIndex);
        });
    }

    @Override
    public void markApplied(long raftIndex) {
        jdbc.update("UPDATE ledger_state SET last_applied = GREATEST(last_applied, ?) WHERE id = 1", raftIndex);
    }
}
