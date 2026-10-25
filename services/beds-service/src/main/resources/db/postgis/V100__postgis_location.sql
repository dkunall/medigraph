-- Optional migration, applied only with the 'postgis' profile (needs the PostGIS extension, e.g. postgis/postgis image).
CREATE EXTENSION IF NOT EXISTS postgis;
ALTER TABLE hospital ADD COLUMN IF NOT EXISTS geog geography(Point, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography) STORED;
CREATE INDEX IF NOT EXISTS idx_hospital_geog ON hospital USING GIST (geog);
