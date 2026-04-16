-- =====================================================================
-- SmartTriage Database Schema V1
-- Production-grade schema for Emergency Department clinical workflow
--
-- Tables: hospitals, users, patients, visits, vital_signs,
--         triage_records, clinical_alerts
--
-- Design principles:
--   - UUID primary keys (globally unique, no sequential exposure)
--   - Audit fields on all tables (created_at, updated_at, created_by)
--   - Soft delete (is_active flag) — never physically delete clinical data
--   - Optimistic locking (version column)
--   - Strategic indexing for ED dashboard performance
--   - PostgreSQL-native UUID and timestamptz types
-- =====================================================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================================
-- 1. HOSPITALS — Multi-tenancy anchor
-- =====================================================================
CREATE TABLE hospitals (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    hospital_code   VARCHAR(20)  NOT NULL UNIQUE,
    address         VARCHAR(500),
    city            VARCHAR(100),
    province        VARCHAR(100),
    country         VARCHAR(3),
    phone_number    VARCHAR(20),
    email           VARCHAR(255),
    tier            VARCHAR(20),
    bed_capacity    INTEGER,
    ed_capacity     INTEGER,
    icu_capacity    INTEGER,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255)
);

CREATE INDEX idx_hospital_code   ON hospitals(hospital_code);
CREATE INDEX idx_hospital_active ON hospitals(is_active);

-- =====================================================================
-- 2. USERS — System users (clinicians, admins, nurses)
-- =====================================================================
CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    first_name            VARCHAR(100) NOT NULL,
    last_name             VARCHAR(100) NOT NULL,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    phone_number          VARCHAR(20),
    role                  VARCHAR(30)  NOT NULL,
    employee_number       VARCHAR(50),
    professional_license  VARCHAR(50),
    department            VARCHAR(100),
    hospital_id           UUID         NOT NULL REFERENCES hospitals(id),
    account_locked        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    version               BIGINT       DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ,
    created_by            VARCHAR(255),
    last_modified_by      VARCHAR(255)
);

CREATE INDEX idx_user_email           ON users(email);
CREATE INDEX idx_user_hospital        ON users(hospital_id);
CREATE INDEX idx_user_role            ON users(role);
CREATE INDEX idx_user_active          ON users(is_active);
CREATE INDEX idx_user_employee_number ON users(employee_number);

-- =====================================================================
-- 3. PATIENTS — Clinical subjects
-- =====================================================================
CREATE TABLE patients (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    date_of_birth           DATE,
    gender                  VARCHAR(10),
    national_id             VARCHAR(30),
    medical_record_number   VARCHAR(30),
    phone_number            VARCHAR(20),
    address                 VARCHAR(500),
    emergency_contact_name  VARCHAR(200),
    emergency_contact_phone VARCHAR(20),
    blood_type              VARCHAR(5),
    known_allergies         TEXT,
    chronic_conditions      TEXT,
    hospital_id             UUID         NOT NULL REFERENCES hospitals(id),
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    version                 BIGINT       DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    last_modified_by        VARCHAR(255)
);

CREATE INDEX idx_patient_hospital    ON patients(hospital_id);
CREATE INDEX idx_patient_national_id ON patients(national_id);
CREATE INDEX idx_patient_mrn         ON patients(medical_record_number);
CREATE INDEX idx_patient_active      ON patients(is_active);
CREATE INDEX idx_patient_dob         ON patients(date_of_birth);
CREATE INDEX idx_patient_name        ON patients(last_name, first_name);

-- =====================================================================
-- 4. VISITS — ED encounters (central workflow record)
-- =====================================================================
CREATE TABLE visits (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    patient_id              UUID         NOT NULL REFERENCES patients(id),
    hospital_id             UUID         NOT NULL REFERENCES hospitals(id),
    visit_number            VARCHAR(30)  NOT NULL UNIQUE,
    arrival_mode            VARCHAR(20),
    arrival_time            TIMESTAMPTZ  NOT NULL,
    chief_complaint         TEXT,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'REGISTERED',
    current_triage_category VARCHAR(10),
    current_tews_score      INTEGER,
    triage_time             TIMESTAMPTZ,
    assessment_start_time   TIMESTAMPTZ,
    disposition_type        VARCHAR(30),
    disposition_time        TIMESTAMPTZ,
    disposition_notes       TEXT,
    referring_facility      VARCHAR(255),
    is_pediatric            BOOLEAN      NOT NULL DEFAULT FALSE,
    retriage_count          INTEGER      NOT NULL DEFAULT 0,
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    version                 BIGINT       DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    last_modified_by        VARCHAR(255)
);

CREATE INDEX idx_visit_patient         ON visits(patient_id);
CREATE INDEX idx_visit_hospital        ON visits(hospital_id);
CREATE INDEX idx_visit_status          ON visits(status);
CREATE INDEX idx_visit_triage_category ON visits(current_triage_category);
CREATE INDEX idx_visit_arrival         ON visits(arrival_time);
CREATE INDEX idx_visit_active          ON visits(is_active);

-- =====================================================================
-- 5. VITAL_SIGNS — Patient vital sign snapshots
-- =====================================================================
CREATE TABLE vital_signs (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    visit_id         UUID         NOT NULL REFERENCES visits(id),
    recorded_at      TIMESTAMPTZ  NOT NULL,
    respiratory_rate INTEGER,
    heart_rate       INTEGER,
    systolic_bp      INTEGER,
    diastolic_bp     INTEGER,
    temperature      DOUBLE PRECISION,
    spo2             INTEGER,
    avpu             VARCHAR(15),
    blood_glucose    DOUBLE PRECISION,
    pain_score       INTEGER,
    gcs_score        INTEGER,
    source           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL_ENTRY',
    device_id        VARCHAR(50),
    notes            TEXT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255)
);

CREATE INDEX idx_vital_visit       ON vital_signs(visit_id);
CREATE INDEX idx_vital_recorded_at ON vital_signs(recorded_at);
CREATE INDEX idx_vital_source      ON vital_signs(source);
CREATE INDEX idx_vital_active      ON vital_signs(is_active);

-- =====================================================================
-- 6. TRIAGE_RECORDS — Triage/re-triage clinical records
-- =====================================================================
CREATE TABLE triage_records (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    visit_id                    UUID         NOT NULL REFERENCES visits(id),
    triaged_by_id               UUID         REFERENCES users(id),
    vital_signs_id              UUID         REFERENCES vital_signs(id),
    triage_time                 TIMESTAMPTZ  NOT NULL,
    has_airway_compromise       BOOLEAN      NOT NULL DEFAULT FALSE,
    has_breathing_distress      BOOLEAN      NOT NULL DEFAULT FALSE,
    has_circulation_compromise  BOOLEAN      NOT NULL DEFAULT FALSE,
    has_coma                    BOOLEAN      NOT NULL DEFAULT FALSE,
    has_convulsions             BOOLEAN      NOT NULL DEFAULT FALSE,
    has_severe_dehydration      BOOLEAN      NOT NULL DEFAULT FALSE,
    mobility                    VARCHAR(15),
    avpu                        VARCHAR(15),
    trauma_status               VARCHAR(15),
    tews_score                  INTEGER      NOT NULL,
    triage_category             VARCHAR(10)  NOT NULL,
    is_retriage                 BOOLEAN      NOT NULL DEFAULT FALSE,
    is_system_triggered         BOOLEAN      NOT NULL DEFAULT FALSE,
    previous_category           VARCHAR(10),
    clinical_notes              TEXT,
    presenting_complaints       TEXT,
    is_active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    version                     BIGINT       DEFAULT 0,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(255),
    last_modified_by            VARCHAR(255)
);

CREATE INDEX idx_triage_visit    ON triage_records(visit_id);
CREATE INDEX idx_triage_category ON triage_records(triage_category);
CREATE INDEX idx_triage_time     ON triage_records(triage_time);
CREATE INDEX idx_triage_active   ON triage_records(is_active);

-- =====================================================================
-- 7. CLINICAL_ALERTS — System-generated clinical alerts
-- =====================================================================
CREATE TABLE clinical_alerts (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    visit_id          UUID         NOT NULL REFERENCES visits(id),
    alert_type        VARCHAR(30)  NOT NULL,
    severity          VARCHAR(15)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    message           TEXT         NOT NULL,
    is_acknowledged   BOOLEAN      NOT NULL DEFAULT FALSE,
    acknowledged_by_id UUID        REFERENCES users(id),
    acknowledged_at   TIMESTAMPTZ,
    auto_generated    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    version           BIGINT       DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(255),
    last_modified_by  VARCHAR(255)
);

CREATE INDEX idx_alert_visit        ON clinical_alerts(visit_id);
CREATE INDEX idx_alert_type         ON clinical_alerts(alert_type);
CREATE INDEX idx_alert_severity     ON clinical_alerts(severity);
CREATE INDEX idx_alert_acknowledged ON clinical_alerts(is_acknowledged);
CREATE INDEX idx_alert_created      ON clinical_alerts(created_at);
CREATE INDEX idx_alert_active       ON clinical_alerts(is_active);
