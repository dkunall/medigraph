-- records-service owns ciphertext, wrapped data keys and nothing else. Patient identity lives in identity-mpi-service,
-- so patient_id is a plain Health ID (no cross-service foreign key).

CREATE TABLE patient_key (
    patient_id  CHAR(14)    PRIMARY KEY,
    version     INT         NOT NULL,
    kek_id      VARCHAR(64) NOT NULL,                  -- which KEK wrapped this DEK (rotation = rewrap this row only)
    iv          BYTEA       NOT NULL,
    wrapped     BYTEA       NOT NULL,                  -- DEK wrapped by the KEK, patient id as AAD
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    rewrapped_at TIMESTAMPTZ
);
CREATE INDEX idx_patient_key_kek ON patient_key (kek_id);

-- Shredding deletes the key row; the tombstone distinguishes "destroyed" (410) from "never had data".
CREATE TABLE patient_key_tombstone (
    patient_id   CHAR(14)    PRIMARY KEY,
    shredded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE health_record (
    id               UUID        PRIMARY KEY,
    patient_id       CHAR(14)    NOT NULL,
    resource_type    VARCHAR(32) NOT NULL,
    category         VARCHAR(16) NOT NULL,
    key_version      INT         NOT NULL,
    iv               BYTEA       NOT NULL,
    ciphertext       BYTEA       NOT NULL,             -- AES-256-GCM, record id as AAD
    content_hash     CHAR(64)    NOT NULL,             -- SHA-256 of the plaintext FHIR JSON (anchored on the ledger)
    ledger_entry_id  VARCHAR(80) NOT NULL UNIQUE,
    facility         VARCHAR(64) NOT NULL,
    created_by       VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_record_patient_category ON health_record (patient_id, category, created_at);
