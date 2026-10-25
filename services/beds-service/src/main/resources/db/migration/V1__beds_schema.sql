-- beds-service owns its schema.
CREATE TABLE hospital (
    id    VARCHAR(64)  PRIMARY KEY,
    name  VARCHAR(200) NOT NULL,
    lat   DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon   DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180)
);
CREATE INDEX ix_hospital_lat_lon ON hospital (lat, lon);   -- bounding-box prefilter of the default locator

CREATE TABLE bed_inventory (
    hospital_id VARCHAR(64) PRIMARY KEY REFERENCES hospital (id),
    total       INT    NOT NULL CHECK (total >= 0),
    occupied    INT    NOT NULL CHECK (occupied >= 0),
    held        INT    NOT NULL CHECK (held >= 0),
    version     BIGINT NOT NULL,                     -- optimistic lock (compare-and-set)
    CHECK (occupied + held <= total)                 -- last line of defence against overselling
);

CREATE TABLE bed_hold (
    hold_id     VARCHAR(64) PRIMARY KEY,
    hospital_id VARCHAR(64) NOT NULL REFERENCES hospital (id),
    patient_id  CHAR(14)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','CONFIRMED','RELEASED','EXPIRED'))
);
CREATE INDEX ix_bed_hold_active_expiry ON bed_hold (expires_at) WHERE status = 'ACTIVE';
