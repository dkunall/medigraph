-- prescription-service owns its schema; patient ids are references into identity-mpi-service (no cross-service FK).
CREATE TABLE prescription_override (
    override_id VARCHAR(64) PRIMARY KEY,
    doctor_id   VARCHAR(128) NOT NULL,
    patient_id  CHAR(14)     NOT NULL,
    drug        VARCHAR(128) NOT NULL,
    alerts      JSONB        NOT NULL,
    reason      TEXT         NOT NULL,   -- stays in this schema; only its hash goes to the ledger
    at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_override_patient ON prescription_override (patient_id, at);

CREATE TABLE prescription (
    id            UUID PRIMARY KEY,
    patient_id    CHAR(14)     NOT NULL,
    prescriber_id VARCHAR(128) NOT NULL,
    facility      VARCHAR(64),
    drug_input    VARCHAR(128) NOT NULL,
    drug          VARCHAR(128),
    dosage        VARCHAR(256),
    outcome       VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL CHECK (status IN ('NOT_ISSUED', 'PENDING_RECORD', 'RECORDED')),
    override_id   VARCHAR(64) REFERENCES prescription_override (override_id),
    record_id     VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_prescription_patient ON prescription (patient_id, created_at);
CREATE INDEX ix_prescription_pending ON prescription (status) WHERE status = 'PENDING_RECORD';
