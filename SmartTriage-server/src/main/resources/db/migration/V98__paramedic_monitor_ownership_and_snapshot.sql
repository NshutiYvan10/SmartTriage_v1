-- V98 — Paramedic self-registered field monitor (Issue 2).
-- (1) Device ownership: a paramedic's own monitor is owned by the user, not a
--     hospital (hospital-agnostic — works at any destination hospital).
-- (2) Latest device-keyed vitals snapshot: the most recent reading the device
--     reported, kept per-device (no visit/session), so a paramedic can
--     "pull from my monitor" into the EMS field-vitals before a Visit exists.

ALTER TABLE iot_devices ADD COLUMN registered_by_user_id UUID;
CREATE INDEX idx_iot_device_registered_by ON iot_devices (registered_by_user_id);

ALTER TABLE iot_devices ADD COLUMN last_heart_rate   INTEGER;
ALTER TABLE iot_devices ADD COLUMN last_resp_rate    INTEGER;
ALTER TABLE iot_devices ADD COLUMN last_spo2         INTEGER;
ALTER TABLE iot_devices ADD COLUMN last_systolic_bp  INTEGER;
ALTER TABLE iot_devices ADD COLUMN last_diastolic_bp INTEGER;
ALTER TABLE iot_devices ADD COLUMN last_temperature  DOUBLE PRECISION;
ALTER TABLE iot_devices ADD COLUMN last_glucose      DOUBLE PRECISION;
-- TIMESTAMPTZ to match the table's existing Instant columns (last_heartbeat_at /
-- last_data_at in V6) so ddl-auto=validate passes (Hibernate maps Instant → timestamptz).
ALTER TABLE iot_devices ADD COLUMN last_vitals_at    TIMESTAMPTZ;
