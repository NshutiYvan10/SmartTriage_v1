-- V117: live detection annotation on monitoring sessions.
--
-- The deterioration engine already KNOWS what it detected (SPO2_OVERRIDE,
-- SEPSIS_PATTERN, RAPID_DECLINE, ...) but only encoded it inside alert
-- message text — the monitoring pages could show "worsening" but never
-- WHAT was detected. These columns carry the engine's current detection
-- so the UI can render a labelled badge ("SEPSIS PATTERN — screening
-- required") and clear it honestly when the patient recovers.
ALTER TABLE device_sessions
    ADD COLUMN last_detected_pattern VARCHAR(40),
    ADD COLUMN last_detected_at TIMESTAMPTZ;
