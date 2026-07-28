-- V119: monitoring_events — the append-only event log of the continuous
-- monitoring engine.
--
-- The engine's detections were transient: the session row keeps only the
-- LATEST pattern (V117) and the alert layer deliberately de-duplicates,
-- so the clinical SEQUENCE ("abnormal vitals 13:49 → rapid SpO2 decline
-- 13:50 → sepsis pattern 13:51") evaporated. That sequence changes the
-- differential (what came first?) and is what a doctor reads at handover.
--
-- One row per TRANSITION (pattern detected/changed/cleared, trend change,
-- auto-retriage, session lifecycle) — never per reading — so an episode
-- is 5-20 rows, not thousands. Rows are written even when the matching
-- alert is dedup-suppressed: alerts page humans, this table records
-- history. Each row carries the vitals context that triggered it so the
-- entry is clinically defensible on its own.
CREATE TABLE monitoring_events (
    id               UUID PRIMARY KEY,
    visit_id         UUID NOT NULL REFERENCES visits(id),
    session_id       UUID NULL REFERENCES device_sessions(id),
    event_type       VARCHAR(30) NOT NULL,
    label            VARCHAR(160) NOT NULL,
    detail           TEXT,
    occurred_at      TIMESTAMPTZ NOT NULL,
    -- Vitals context at the moment of the event (nullable — lifecycle
    -- events may have no reading yet).
    heart_rate       INTEGER,
    spo2             INTEGER,
    respiratory_rate INTEGER,
    systolic_bp      INTEGER,
    temperature      DOUBLE PRECISION,
    -- BaseEntity columns — created_at / updated_at / version / is_active
    -- + created_by / last_modified_by, matching every other entity.
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    version          BIGINT,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       VARCHAR(100),
    last_modified_by VARCHAR(100)
);

-- The one query shape: "events for this visit in the last N hours, in order".
CREATE INDEX idx_monitoring_events_visit_time
    ON monitoring_events (visit_id, occurred_at);
