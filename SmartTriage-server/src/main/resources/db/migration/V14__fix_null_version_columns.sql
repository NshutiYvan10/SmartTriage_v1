-- Fix NULL version columns on all tables that use BaseEntity's @Version field.
-- Hibernate's optimistic locking requires a non-null version value.

UPDATE iot_devices SET version = 0 WHERE version IS NULL;
UPDATE users SET version = 0 WHERE version IS NULL;
UPDATE hospitals SET version = 0 WHERE version IS NULL;
UPDATE patients SET version = 0 WHERE version IS NULL;
UPDATE visits SET version = 0 WHERE version IS NULL;
UPDATE triage_records SET version = 0 WHERE version IS NULL;
UPDATE vital_signs SET version = 0 WHERE version IS NULL;
UPDATE vital_streams SET version = 0 WHERE version IS NULL;
UPDATE device_sessions SET version = 0 WHERE version IS NULL;
UPDATE clinical_alerts SET version = 0 WHERE version IS NULL;
UPDATE clinical_notes SET version = 0 WHERE version IS NULL;
UPDATE diagnoses SET version = 0 WHERE version IS NULL;
UPDATE investigations SET version = 0 WHERE version IS NULL;
UPDATE medication_administrations SET version = 0 WHERE version IS NULL;
UPDATE shift_assignments SET version = 0 WHERE version IS NULL;
