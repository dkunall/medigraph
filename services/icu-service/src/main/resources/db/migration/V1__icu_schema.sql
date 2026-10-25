-- icu-service owns its schema. One alert per patient window: the unique key makes alert persistence idempotent
-- under at-least-once delivery from the Kafka scores topic.
CREATE TABLE icu_alert (
    id           BIGSERIAL PRIMARY KEY,
    patient_id   CHAR(14)    NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    score        INT         NOT NULL CHECK (score BETWEEN 0 AND 20),
    risk         VARCHAR(16) NOT NULL,
    reason       VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_icu_alert_window UNIQUE (patient_id, window_end)
);
CREATE INDEX ix_icu_alert_patient ON icu_alert (patient_id, window_end DESC);
