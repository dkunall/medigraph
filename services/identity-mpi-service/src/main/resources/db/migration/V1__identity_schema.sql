-- identity-mpi-service owns Health IDs, facility registrations (demographics) and the MPI's links/review queue.
CREATE TABLE patient (
    health_id    CHAR(14) PRIMARY KEY,                       -- 13 digits + Verhoeff check digit
    merged_into  CHAR(14) REFERENCES patient (health_id),    -- set when a steward merges a provisional identity
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (merged_into IS NULL OR merged_into <> health_id)
);
CREATE INDEX idx_patient_merged_into ON patient (merged_into) WHERE merged_into IS NOT NULL;

CREATE TABLE patient_registration (
    record_id       VARCHAR(80) PRIMARY KEY,                 -- facility-scoped source record id
    health_id       CHAR(14)    NOT NULL REFERENCES patient (health_id),
    facility        VARCHAR(64) NOT NULL,
    local_mrn       VARCHAR(64) NOT NULL,
    given_name      VARCHAR(100),
    family_name     VARCHAR(100),
    birth_date      DATE,
    gender          VARCHAR(16),
    phone           VARCHAR(32),
    city            VARCHAR(100),
    match_decision  VARCHAR(16) NOT NULL,
    match_weight    DOUBLE PRECISION,
    erased_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (facility, local_mrn)
);
CREATE INDEX idx_registration_health_id ON patient_registration (health_id);

CREATE TABLE mpi_link (
    record_a    VARCHAR(80) NOT NULL REFERENCES patient_registration (record_id),
    record_b    VARCHAR(80) NOT NULL REFERENCES patient_registration (record_id),
    reason      VARCHAR(16) NOT NULL,                        -- AUTO_LINK | STEWARD
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (record_a, record_b)
);

CREATE TABLE review_task (
    id                   VARCHAR(64) PRIMARY KEY,
    record_id            VARCHAR(80) NOT NULL,
    candidate_record_id  VARCHAR(80) NOT NULL,
    weight               DOUBLE PRECISION NOT NULL,
    per_field            TEXT NOT NULL,                      -- JSON map field -> weight (explainability)
    status               VARCHAR(16) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    resolved_at          TIMESTAMPTZ
);
CREATE INDEX idx_review_pending ON review_task (created_at) WHERE status = 'PENDING';
