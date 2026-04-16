-- =====================================================================
-- V6 — IoT Integration: Device Registry, Sessions, and Vital Streams
-- =====================================================================
-- Creates the three core IoT tables:
--   1. iot_devices      — device registry with API key authentication
--   2. device_sessions  — device-to-visit linkage (monitoring sessions)
--   3. vital_streams    — high-frequency time-series vital data
--
-- Designed for:
--   - ESP32 multi-parameter monitors streaming at 1-5 second intervals
--   - Real-time deterioration detection and AI-driven auto-retriage
--   - Full audit trail of all device data (including rejected readings)
-- =====================================================================

-- ====================================================================
-- 1. IOT DEVICES — Device Registry
-- ====================================================================
CREATE TABLE iot_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number   VARCHAR(100) NOT NULL,
    device_name     VARCHAR(100) NOT NULL,
    device_type     VARCHAR(30)  NOT NULL,
    hospital_id     UUID NOT NULL REFERENCES hospitals(id),
    api_key         VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'REGISTERED',
    firmware_version VARCHAR(30),
    last_heartbeat_at TIMESTAMPTZ,
    last_data_at    TIMESTAMPTZ,
    battery_level   INTEGER,
    wifi_rssi       INTEGER,
    ip_address      VARCHAR(45),
    mac_address     VARCHAR(17),
    location        VARCHAR(100),
    heartbeat_timeout_seconds INTEGER NOT NULL DEFAULT 30,
    data_interval_seconds     INTEGER NOT NULL DEFAULT 5,
    notes           TEXT,

    -- BaseEntity audit fields
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_iot_device_serial  UNIQUE (serial_number),
    CONSTRAINT uk_iot_device_api_key UNIQUE (api_key)
);

CREATE INDEX idx_iot_device_serial   ON iot_devices (serial_number);
CREATE INDEX idx_iot_device_hospital ON iot_devices (hospital_id);
CREATE INDEX idx_iot_device_status   ON iot_devices (status);
CREATE INDEX idx_iot_device_active   ON iot_devices (is_active);

-- ====================================================================
-- 2. DEVICE SESSIONS — Device-to-Visit monitoring linkage
-- ====================================================================
CREATE TABLE device_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id       UUID NOT NULL REFERENCES iot_devices(id),
    visit_id        UUID NOT NULL REFERENCES visits(id),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    session_active  BOOLEAN     NOT NULL DEFAULT true,
    started_by_name VARCHAR(255),
    ended_by_name   VARCHAR(255),
    end_reason      VARCHAR(255),
    total_readings  BIGINT  NOT NULL DEFAULT 0,
    rejected_readings BIGINT NOT NULL DEFAULT 0,
    alerts_generated  INTEGER NOT NULL DEFAULT 0,
    retriages_triggered INTEGER NOT NULL DEFAULT 0,

    -- BaseEntity audit fields
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_device_session_device       ON device_sessions (device_id);
CREATE INDEX idx_device_session_visit        ON device_sessions (visit_id);
CREATE INDEX idx_device_session_started      ON device_sessions (started_at);
CREATE INDEX idx_device_session_ended        ON device_sessions (ended_at);
CREATE INDEX idx_device_session_active_flag  ON device_sessions (session_active);
CREATE INDEX idx_device_session_active       ON device_sessions (is_active);

-- ====================================================================
-- 3. VITAL STREAMS — High-frequency time-series vital data
-- ====================================================================
CREATE TABLE vital_streams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id        UUID NOT NULL REFERENCES visits(id),
    device_id       VARCHAR(100) NOT NULL,
    session_id      UUID,
    captured_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,

    -- Raw vital readings
    heart_rate      INTEGER,
    spo2            INTEGER,
    respiratory_rate INTEGER,
    temperature     DOUBLE PRECISION,
    systolic_bp     INTEGER,
    diastolic_bp    INTEGER,
    blood_glucose   DOUBLE PRECISION,

    -- ECG data
    ecg_waveform    TEXT,
    ecg_rhythm      VARCHAR(30),
    ecg_qrs_duration INTEGER,

    -- Signal quality and metadata
    signal_quality  VARCHAR(15) NOT NULL DEFAULT 'UNKNOWN',
    spo2_perfusion_index DOUBLE PRECISION,
    is_validated    BOOLEAN NOT NULL DEFAULT false,
    rejection_reason VARCHAR(255),
    battery_level   INTEGER,
    wifi_rssi       INTEGER,
    sequence_number BIGINT,

    -- BaseEntity audit fields
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Performance-critical indexes for time-series queries
CREATE INDEX idx_vs_visit      ON vital_streams (visit_id);
CREATE INDEX idx_vs_device     ON vital_streams (device_id);
CREATE INDEX idx_vs_session    ON vital_streams (session_id);
CREATE INDEX idx_vs_timestamp  ON vital_streams (captured_at);
CREATE INDEX idx_vs_visit_time ON vital_streams (visit_id, captured_at);
CREATE INDEX idx_vs_active     ON vital_streams (is_active);

-- Composite index for the most common query: validated readings for a visit in time order
CREATE INDEX idx_vs_visit_validated ON vital_streams (visit_id, is_validated, is_active, captured_at);
