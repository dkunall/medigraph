-- Raft persistent state (Raft paper, Figure 2): must be durable before the node answers any RPC.
CREATE TABLE raft_hard_state (
    id           SMALLINT PRIMARY KEY CHECK (id = 1),
    current_term BIGINT      NOT NULL CHECK (current_term >= 0),
    voted_for    VARCHAR(64)
);

CREATE TABLE raft_log (
    log_index BIGINT PRIMARY KEY CHECK (log_index > 0),
    term      BIGINT NOT NULL CHECK (term > 0),
    command   TEXT          -- JSON BlockProposal; NULL for the leader's no-op entry
);

-- Committed blocks (the replicated state machine) and how far the Raft log has been applied to them.
CREATE TABLE ledger_block (
    height          BIGINT PRIMARY KEY CHECK (height >= 0),
    previous_hash   CHAR(64) NOT NULL,
    merkle_root     CHAR(64) NOT NULL,
    timestamp_millis BIGINT  NOT NULL,
    hash            CHAR(64) NOT NULL UNIQUE,
    entries         TEXT     NOT NULL,
    raft_index      BIGINT   NOT NULL UNIQUE
);

CREATE TABLE ledger_state (
    id           SMALLINT PRIMARY KEY CHECK (id = 1),
    last_applied BIGINT NOT NULL CHECK (last_applied >= 0)
);
INSERT INTO ledger_state (id, last_applied) VALUES (1, 0);
