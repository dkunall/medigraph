-- Idempotency-Key store for write APIs. scope_key = caller + method + path + client key.
CREATE TABLE idempotency_key (
    scope_key        VARCHAR(400) PRIMARY KEY,
    request_hash     CHAR(64)     NOT NULL,              -- SHA-256 of the request body
    state            VARCHAR(16)  NOT NULL,              -- IN_PROGRESS | COMPLETED
    response_status  INT,
    content_type     VARCHAR(100),
    response_body    BYTEA,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_idempotency_created ON idempotency_key (created_at);
