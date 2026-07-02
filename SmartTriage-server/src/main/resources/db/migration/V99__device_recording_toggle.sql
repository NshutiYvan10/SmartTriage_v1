-- V99 — Monitor Management: per-device recording toggle.
--
-- A paramedic-owned field monitor needs a "stop recording" control that is
-- SOFTER than taking the device out of service: between patients the crew
-- pauses recording so the device stays paired/online (heartbeats continue)
-- but its vitals snapshot is NOT overwritten — preventing a later
-- "Pull from my monitor" from grabbing the PREVIOUS patient's numbers.
--
-- recording_enabled = TRUE keeps every existing device behaving exactly as
-- before (telemetry stores the last_* snapshot).
ALTER TABLE iot_devices
    ADD COLUMN recording_enabled BOOLEAN NOT NULL DEFAULT TRUE;
