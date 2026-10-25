package com.medigraph.platform.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed store. The claim is a single {@code INSERT ... ON CONFLICT DO NOTHING}: the primary key decides the
 * race between two concurrent duplicates, so exactly one of them executes. An IN_PROGRESS row older than
 * {@code lease} (the executing instance died) can be taken over by a conditional UPDATE. Each statement auto-commits,
 * independent of the business transaction (documented trade-off: if the process dies after the business commit
 * but before {@link #complete}, the retry after the lease re-executes; business-level dedup, e.g. unique
 * constraints, is the last line of defence).
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final Duration lease;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, Duration lease) {
        this.jdbc = jdbc;
        this.lease = lease;
    }

    @Override
    public Optional<Entry> claim(String scope, String requestHash) {
        int inserted = jdbc.update("INSERT INTO idempotency_key (scope_key, request_hash, state) VALUES (?, ?, 'IN_PROGRESS') "
                + "ON CONFLICT (scope_key) DO NOTHING", scope, requestHash);
        if (inserted == 1) {
            return Optional.empty();
        }
        int takenOver = jdbc.update("UPDATE idempotency_key SET created_at = now() WHERE scope_key = ? AND request_hash = ? "
                + "AND state = 'IN_PROGRESS' AND created_at < now() - (? * interval '1 millisecond')", scope, requestHash, lease.toMillis());
        if (takenOver == 1) {
            return Optional.empty();
        }
        List<Entry> rows = jdbc.query("SELECT request_hash, state, response_status, content_type, response_body "
                        + "FROM idempotency_key WHERE scope_key = ?",
                (rs, i) -> new Entry(rs.getString(1), State.valueOf(rs.getString(2)), rs.getInt(3), rs.getString(4),
                        rs.getBytes(5)), scope);
        if (rows.isEmpty()) { // released between our insert and select: claim again
            return claim(scope, requestHash);
        }
        return Optional.of(rows.get(0));
    }

    @Override
    public void complete(String scope, int status, String contentType, byte[] body) {
        jdbc.update("UPDATE idempotency_key SET state = 'COMPLETED', response_status = ?, content_type = ?, "
                + "response_body = ?, completed_at = now() WHERE scope_key = ?", status, contentType, body, scope);
    }

    @Override
    public void release(String scope) {
        jdbc.update("DELETE FROM idempotency_key WHERE scope_key = ? AND state = 'IN_PROGRESS'", scope);
    }
}
