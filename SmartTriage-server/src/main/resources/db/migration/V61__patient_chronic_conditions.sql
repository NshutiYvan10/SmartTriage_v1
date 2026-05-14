-- ============================================================
-- V61 — Structured patient chronic conditions
-- ============================================================
--
-- Mirrors V58 (patient_allergies) for chronic conditions. The
-- legacy free-text column patients.chronic_conditions stays in
-- place as a fallback for un-migrated records; new entries flow
-- through this structured table so:
--
--   • the safety engine (renal-risk, teratogen, geriatric paths)
--     gets a reliable signal instead of substring-matching free
--     text,
--   • the doctor's chart shows structured "Hypertension /
--     Controlled / on amlodipine 5mg" cards instead of a paragraph,
--   • the audit trail records who said what, when, and why a
--     condition was resolved.
--
-- No severity column — chronic conditions are graded by status
-- (ACTIVE / CONTROLLED / IN_REMISSION / RESOLVED), captured by
-- com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus.
-- ACTIVE + CONTROLLED feed the safety checks; IN_REMISSION and
-- RESOLVED stay on the chart for history but don't gate.
-- ============================================================

CREATE TABLE patient_chronic_conditions (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id               UUID         NOT NULL REFERENCES patients(id),
    condition_code           VARCHAR(40),
    condition_name           VARCHAR(200) NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    notes                    VARCHAR(500),
    onset_date               DATE,
    recorded_by_name         VARCHAR(200),
    recorded_at              TIMESTAMP,
    resolved_by_name         VARCHAR(200),
    resolved_at              TIMESTAMP,
    resolve_reason           VARCHAR(500),
    -- BaseEntity columns
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP,
    created_by               VARCHAR(255),
    last_modified_by         VARCHAR(255),
    is_active                BOOLEAN      NOT NULL DEFAULT true,
    version                  BIGINT
);

CREATE INDEX idx_patient_chronic_patient ON patient_chronic_conditions(patient_id);
CREATE INDEX idx_patient_chronic_code    ON patient_chronic_conditions(condition_code);
CREATE INDEX idx_patient_chronic_status  ON patient_chronic_conditions(status);
CREATE INDEX idx_patient_chronic_active  ON patient_chronic_conditions(is_active);

ALTER TABLE patient_chronic_conditions
    ADD CONSTRAINT ck_patient_chronic_status
    CHECK (status IN ('ACTIVE', 'CONTROLLED', 'IN_REMISSION', 'RESOLVED'));
