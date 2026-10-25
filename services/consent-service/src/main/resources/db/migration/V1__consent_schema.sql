-- consent-service owns consents, break-glass grants, signing-key public halves and patient notifications.
-- patient_id is a Health ID owned by identity-mpi-service: no cross-service foreign key, by design.
CREATE TABLE consent_signing_key (
    key_id      VARCHAR(80) PRIMARY KEY,
    algorithm   VARCHAR(16) NOT NULL,
    public_key  BYTEA       NOT NULL,                        -- X.509 SubjectPublicKeyInfo; private keys never stored here
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE consent (
    consent_id     VARCHAR(64)  PRIMARY KEY,
    patient_id     CHAR(14)     NOT NULL,
    grantee        VARCHAR(64)  NOT NULL,
    categories     VARCHAR(200) NOT NULL,
    purpose        VARCHAR(16)  NOT NULL,
    issued_at      TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    signer_key_id  VARCHAR(80)  NOT NULL REFERENCES consent_signing_key (key_id),
    signature      TEXT         NOT NULL,
    revoked_at     TIMESTAMPTZ
);
CREATE INDEX idx_consent_patient ON consent (patient_id);

CREATE TABLE break_glass (
    grant_id     VARCHAR(64)  PRIMARY KEY,
    patient_id   CHAR(14)     NOT NULL,
    requester    VARCHAR(64)  NOT NULL,
    facility     VARCHAR(64)  NOT NULL,
    reason       TEXT         NOT NULL,                      -- off-chain; only its hash is on the ledger
    reason_hash  CHAR(64)     NOT NULL,
    scope        VARCHAR(200) NOT NULL,
    granted_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_break_glass_patient ON break_glass (patient_id);

CREATE TABLE notification (
    id          BIGSERIAL   PRIMARY KEY,
    patient_id  CHAR(14)    NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    message     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_patient ON notification (patient_id);
