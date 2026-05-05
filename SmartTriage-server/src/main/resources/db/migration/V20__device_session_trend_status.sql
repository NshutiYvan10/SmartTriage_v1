-- V20: Persist server-computed patient trend on DeviceSession.
-- Trend is derived by ContinuousMonitoringEngine from recent VitalStream data
-- with hysteresis (two consecutive agreeing classifications required before
-- trend_status changes). trend_candidate holds the pending value between ticks.

ALTER TABLE device_sessions
    ADD COLUMN IF NOT EXISTS trend_status VARCHAR(16) DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS trend_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS trend_candidate VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_device_session_trend_status
    ON device_sessions (trend_status)
    WHERE session_active = TRUE;
