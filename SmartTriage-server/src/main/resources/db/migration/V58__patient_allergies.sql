-- ============================================================
-- V58 — Structured patient allergies
-- ============================================================
--
-- Workflow 2 (medication allergy safety). Replaces the legacy
-- free-text Patient.known_allergies column with a normalised
-- patient_allergies table that carries:
--   • optional FK to drug_formulary (no more typo misses)
--   • clinical severity (MILD / MODERATE / SEVERE / ANAPHYLAXIS /
--     UNKNOWN) — drives the prescribe-time safety dialog flavour
--     and the alert severity on override
--   • reaction description (so the prescriber can decide whether
--     an override is clinically reasonable)
--   • verification status (PATIENT_REPORTED / CONFIRMED / REFUTED)
--     — a refuted allergy stops firing safety alerts without
--     losing the audit trail
--
-- The legacy patient.known_allergies column is intentionally NOT
-- dropped — un-migrated records still use it, and the safety engine
-- falls back to it when no structured rows exist for the patient.
-- A future round can backfill + drop.
-- ============================================================

CREATE TABLE patient_allergies (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id               UUID         NOT NULL REFERENCES patients(id),
    allergen_formulary_id    UUID         NULL REFERENCES drug_formularies(id),
    allergen_name            VARCHAR(200) NOT NULL,
    severity                 VARCHAR(20)  NOT NULL,
    reaction                 VARCHAR(500),
    onset_date               DATE,
    verification_status      VARCHAR(32)  NOT NULL DEFAULT 'PATIENT_REPORTED',
    recorded_by_name         VARCHAR(200),
    recorded_at              TIMESTAMP,
    refuted_by_name          VARCHAR(200),
    refuted_at               TIMESTAMP,
    refute_reason            VARCHAR(500),
    -- BaseEntity columns
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP,
    created_by               VARCHAR(255),
    last_modified_by         VARCHAR(255),
    is_active                BOOLEAN      NOT NULL DEFAULT true,
    version                  BIGINT
);

CREATE INDEX idx_patient_allergy_patient        ON patient_allergies(patient_id);
CREATE INDEX idx_patient_allergy_allergen_name  ON patient_allergies(allergen_name);
CREATE INDEX idx_patient_allergy_formulary      ON patient_allergies(allergen_formulary_id);
CREATE INDEX idx_patient_allergy_active         ON patient_allergies(is_active);
CREATE INDEX idx_patient_allergy_verification   ON patient_allergies(verification_status);

-- Severity values must match com.smartTriage.smartTriage_server.common.enums.AllergySeverity.
ALTER TABLE patient_allergies
    ADD CONSTRAINT ck_patient_allergy_severity
    CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'ANAPHYLAXIS', 'UNKNOWN'));

ALTER TABLE patient_allergies
    ADD CONSTRAINT ck_patient_allergy_verification
    CHECK (verification_status IN ('PATIENT_REPORTED', 'CONFIRMED', 'REFUTED'));
