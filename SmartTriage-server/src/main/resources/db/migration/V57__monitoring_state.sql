-- V57 — Continuous-monitoring lifecycle state on device_sessions
--
-- Replaces the binary `session_active` flag with a clinical-facing
-- MonitoringState that captures the full lifecycle:
--   NOT_STARTED, STARTING, LIVE, DEGRADED, STALLED, PAUSED,
--   DISCONNECTED, ENDED
--
-- `session_active` stays as the "is the row open or closed" flag —
-- true for everything except ENDED — but the rich state lives on
-- this new column.
--
-- We also add audit fields for the new lifecycle controls
-- (Pause / Resume / End) and a continuity-group id that links the
-- two sides of a transfer-driven session split into one timeline.

ALTER TABLE device_sessions
    ADD COLUMN monitoring_state VARCHAR(20)        NOT NULL DEFAULT 'LIVE',
    ADD COLUMN monitoring_state_at TIMESTAMPTZ                NULL,
    ADD COLUMN paused_at         TIMESTAMPTZ                  NULL,
    ADD COLUMN paused_by_name    VARCHAR(255)                 NULL,
    ADD COLUMN resumed_at        TIMESTAMPTZ                  NULL,
    ADD COLUMN resumed_by_name   VARCHAR(255)                 NULL,
    -- A transferred session keeps the same continuity_group_id as
    -- the session that closed on the source bed. Doctor view groups
    -- by this to render one continuous monitoring timeline.
    ADD COLUMN continuity_group_id UUID                       NULL;

-- Existing open rows are streaming today, so backfill LIVE. Closed
-- rows (session_active = false) become ENDED.
UPDATE device_sessions
SET monitoring_state    = CASE WHEN session_active THEN 'LIVE' ELSE 'ENDED' END,
    monitoring_state_at = COALESCE(started_at, created_at);

-- Index supports the state-watcher's "find all non-terminal sessions"
-- query and the clinical "show me what's NOT LIVE" filter.
CREATE INDEX idx_device_session_monitoring_state
    ON device_sessions (monitoring_state)
    WHERE is_active = TRUE;

-- Partial index for continuity-group lookups (most rows null).
CREATE INDEX idx_device_session_continuity_group
    ON device_sessions (continuity_group_id)
    WHERE continuity_group_id IS NOT NULL;
