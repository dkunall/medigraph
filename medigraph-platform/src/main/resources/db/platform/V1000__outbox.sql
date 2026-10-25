-- Transactional outbox (shared by every service, in that service's own schema).
-- Rows are written in the business transaction; OutboxRelay delivers them after commit (at-least-once).
CREATE TABLE outbox_message (
    id               BIGSERIAL PRIMARY KEY,
    destination      VARCHAR(32)  NOT NULL,              -- 'ledger' | 'events'
    aggregate_type   VARCHAR(32)  NOT NULL,
    aggregate_id     VARCHAR(80)  NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    payload          TEXT         NOT NULL,              -- JSON: identifiers + hashes only, never PHI
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts         INT          NOT NULL DEFAULT 0,
    published_at     TIMESTAMPTZ,
    last_error       VARCHAR(500)
);
CREATE INDEX idx_outbox_due ON outbox_message (next_attempt_at, id) WHERE published_at IS NULL;
