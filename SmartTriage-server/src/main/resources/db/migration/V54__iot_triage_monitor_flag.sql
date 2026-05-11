-- V54: Triage-zone monitor flag.
--
-- Hospital Admin marks the physical monitor that sits at the triage station
-- with this flag. The triage form's "Pull from Monitor" flow only lists
-- devices where triage_monitor = TRUE AND in_service = TRUE.
--
-- A hospital can have multiple triage monitors (busy EDs run two stations);
-- there is no uniqueness constraint per hospital.

ALTER TABLE iot_devices
  ADD COLUMN triage_monitor BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index — most rows are FALSE; we only ever query for TRUE.
CREATE INDEX idx_iot_device_triage_monitor
  ON iot_devices (hospital_id)
  WHERE triage_monitor = TRUE AND in_service = TRUE;
