-- =====================================================================
-- V5: Medication Administration Record + Clinical Documentation tables
-- =====================================================================
-- Adds four new tables for clinical completeness:
--   1. medication_administrations — MAR entries (prescribe → administer → countersign)
--   2. diagnoses — provisional, confirmed, differential, working diagnoses
--   3. investigations — lab, radiology, ECG, point-of-care tests
--   4. clinical_notes — structured clinical documentation (physical findings,
--      HPC, PMH, allergies, nursing notes, doctor notes, etc.)
-- =====================================================================

-- =====================================================================
-- 1. MEDICATION ADMINISTRATIONS (MAR)
-- =====================================================================
CREATE TABLE medication_administrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id        UUID         NOT NULL REFERENCES visits(id),

    -- Prescription
    drug_name       VARCHAR(255) NOT NULL,
    dose            VARCHAR(100),
    route           VARCHAR(20)  NOT NULL,
    frequency       VARCHAR(50),
    prescribed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    prescribed_by_id   UUID      REFERENCES users(id),
    prescribed_by_name VARCHAR(255),

    -- Administration
    administered_at         TIMESTAMPTZ,
    administered_by_id      UUID      REFERENCES users(id),
    administered_by_name    VARCHAR(255),

    -- Countersigning
    countersigned_by_id     UUID      REFERENCES users(id),
    countersigned_by_name   VARCHAR(255),
    countersigned_at        TIMESTAMPTZ,

    -- Status & notes
    status          VARCHAR(20)  NOT NULL DEFAULT 'PRESCRIBED',
    notes           TEXT,

    -- Audit (from BaseEntity)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_med_admin_visit ON medication_administrations(visit_id);
CREATE INDEX idx_med_admin_prescribed_at ON medication_administrations(prescribed_at);
CREATE INDEX idx_med_admin_status ON medication_administrations(status);
CREATE INDEX idx_med_admin_active ON medication_administrations(is_active);

-- =====================================================================
-- 2. DIAGNOSES
-- =====================================================================
CREATE TABLE diagnoses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id        UUID         NOT NULL REFERENCES visits(id),

    diagnosis_type  VARCHAR(20)  NOT NULL,
    icd_code        VARCHAR(20),
    description     TEXT         NOT NULL,
    diagnosed_by_name VARCHAR(255),
    diagnosed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_primary      BOOLEAN      DEFAULT FALSE,
    notes           TEXT,

    -- Audit (from BaseEntity)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_diagnosis_visit ON diagnoses(visit_id);
CREATE INDEX idx_diagnosis_type ON diagnoses(diagnosis_type);
CREATE INDEX idx_diagnosis_active ON diagnoses(is_active);

-- =====================================================================
-- 3. INVESTIGATIONS
-- =====================================================================
CREATE TABLE investigations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id            UUID         NOT NULL REFERENCES visits(id),

    investigation_type  VARCHAR(30)  NOT NULL,
    test_name           VARCHAR(255) NOT NULL,
    ordered_by_name     VARCHAR(255),
    ordered_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    specimen_collected_at TIMESTAMPTZ,
    resulted_at         TIMESTAMPTZ,
    result              TEXT,
    is_abnormal         BOOLEAN      DEFAULT FALSE,
    is_critical         BOOLEAN      DEFAULT FALSE,
    status              VARCHAR(25)  NOT NULL DEFAULT 'ORDERED',
    priority            VARCHAR(20),
    notes               TEXT,

    -- Audit (from BaseEntity)
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(255),
    last_modified_by    VARCHAR(255),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version             BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_investigation_visit ON investigations(visit_id);
CREATE INDEX idx_investigation_type ON investigations(investigation_type);
CREATE INDEX idx_investigation_status ON investigations(status);
CREATE INDEX idx_investigation_active ON investigations(is_active);

-- =====================================================================
-- 4. CLINICAL NOTES
-- =====================================================================
CREATE TABLE clinical_notes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id        UUID         NOT NULL REFERENCES visits(id),

    note_type       VARCHAR(40)  NOT NULL,
    content         TEXT         NOT NULL,
    recorded_by_name VARCHAR(255),
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    section         VARCHAR(100),

    -- Audit (from BaseEntity)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_clinical_note_visit ON clinical_notes(visit_id);
CREATE INDEX idx_clinical_note_type ON clinical_notes(note_type);
CREATE INDEX idx_clinical_note_recorded_at ON clinical_notes(recorded_at);
CREATE INDEX idx_clinical_note_active ON clinical_notes(is_active);
