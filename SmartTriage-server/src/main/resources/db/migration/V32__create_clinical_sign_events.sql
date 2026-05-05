-- ═══════════════════════════════════════════════════════════════
-- V27 — Clinical sign events table + one-shot backfill from triage_records.
--
-- BACKGROUND
-- ----------
-- Triage captures 54 binary clinical-sign flags (emergency signs, mSAT
-- discriminators, special considerations) frozen at the moment of triage
-- and never updated. A patient who arrived convulsing and was successfully
-- treated still appears as "convulsing" in the chart forever. This blocks
-- the doctor from seeing trajectory and starves the dynamic re-triage
-- engine of the data it would need to escalate a deteriorating patient.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- 1. Creates `clinical_sign_events` (matches the ClinicalSignEvent JPA
--    entity). Event-log model: every change is a row, current state is
--    the latest event per (visit, sign_code).
-- 2. Backfills baseline events for every active visit's positive triage
--    flags. Each positive flag becomes one is_baseline=true PRESENT event
--    tagged with the triage's recorded_at time. Without this, existing
--    visits would render with empty Clinical Signs tabs even though the
--    triage record clearly shows positive findings.
--
-- BACKFILL — single source of truth caveat
-- ----------------------------------------
-- The mapping (triage column → sign code → category) is encoded BOTH
-- here (as 54 SQL INSERT...WHERE clauses) AND in the Java
-- ClinicalSignDefinitions class. Flyway migrations run before the JVM
-- boots, so we can't read the Java mapping at backfill time. Drift
-- between this migration and ClinicalSignDefinitions would silently
-- break — when adding new signs, update both.
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS clinical_sign_events (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255),
    last_modified_by    VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    version             BIGINT,

    visit_id            UUID NOT NULL REFERENCES visits(id),
    patient_id          UUID NOT NULL REFERENCES patients(id),
    sign_code           VARCHAR(60) NOT NULL,
    sign_category       VARCHAR(30) NOT NULL,
    status              VARCHAR(15) NOT NULL,
    numeric_value       DOUBLE PRECISION,
    notes               TEXT,
    recorded_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_by_id      UUID REFERENCES users(id),
    recorded_by_name    VARCHAR(200),
    is_baseline         BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_clinical_sign_visit
    ON clinical_sign_events (visit_id);
CREATE INDEX IF NOT EXISTS idx_clinical_sign_patient
    ON clinical_sign_events (patient_id);
CREATE INDEX IF NOT EXISTS idx_clinical_sign_visit_code_time
    ON clinical_sign_events (visit_id, sign_code, recorded_at);
CREATE INDEX IF NOT EXISTS idx_clinical_sign_recorded_at
    ON clinical_sign_events (recorded_at);
CREATE INDEX IF NOT EXISTS idx_clinical_sign_active
    ON clinical_sign_events (is_active);

-- ════════════════════════════════════════════════════════════════
-- BACKFILL — one INSERT per sign, gated on the column being TRUE on the
-- triage row. Runs once. Existing visits get their baseline timeline
-- starting from the triage record's recorded time.
--
-- Each insert reads visits.id and patients.id by joining triage_records
-- → visits → patients. is_baseline=true marks these as auto-generated.
-- The notes string identifies them as backfill so a future cleanup can
-- find them if needed.
-- ════════════════════════════════════════════════════════════════

-- Reusable template per (column, sign_code, category, optional numeric col).
-- Implemented as one INSERT per sign — verbose but explicit and easy to
-- audit. Each follows the same pattern.

-- ── Emergency (Section 1) ──
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_AIRWAY_COMPROMISE', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_airway_compromise = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_BREATHING_DISTRESS', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_breathing_distress = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_SEVERE_RESPIRATORY_DISTRESS', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_severe_respiratory_distress = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_CARDIAC_ARREST', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_cardiac_arrest = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_UNCONTROLLED_HAEMORRHAGE', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_uncontrolled_haemorrhage = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_STAB_GUN_WOUND_NECK_CHEST', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_stab_gun_wound_neck_chest = TRUE;

-- Convulsions carries glucose
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_CONVULSIONS', 'EMERGENCY', 'PRESENT', t.convulsion_glucose,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_convulsions = TRUE;

-- Coma carries glucose
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_COMA', 'EMERGENCY', 'PRESENT', t.coma_glucose,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_coma = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_HYPOGLYCAEMIA', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_hypoglycaemia = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_PURPURIC_RASH', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_purpuric_rash = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'EMERGENCY_BURN_FACE_INHALATION', 'EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.has_burn_face_inhalation = TRUE;

-- ── Pediatric Emergency (Section 1b) ──
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_CENTRAL_CYANOSIS', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_central_cyanosis = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_PULSE_LOW_OR_ABSENT', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_pulse_low_or_absent = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_COLD_HANDS_COMPOSITE', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_cold_hands_composite = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_COLD_HANDS_LETHARGIC', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_cold_hands_lethargic = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_COLD_HANDS_PULSE_WEAK_FAST', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_cold_hands_pulse_weak_fast = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_COLD_HANDS_CAP_REFILL', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_cold_hands_cap_refill = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_SEVERE_DEHYDRATION', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_severe_dehydration = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_DEHYDRATION_SKIN_PINCH', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_dehydration_skin_pinch = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_DEHYDRATION_LETHARGY', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_dehydration_lethargy = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'PEDS_EMERGENCY_DEHYDRATION_SUNKEN_EYES', 'PEDIATRIC_EMERGENCY', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.child_dehydration_sunken_eyes = TRUE;

-- ── mSAT Very Urgent (Section 3) ──
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_FOCAL_NEUROLOGIC_DEFICIT', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_focal_neurologic_deficit = TRUE;

-- Altered mental status carries vu_neurological_glucose
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_ALTERED_MENTAL_STATUS', 'MSAT_VU', 'PRESENT', t.vu_neurological_glucose,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_altered_mental_status = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_CHEST_PAIN', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_chest_pain = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_POISONING_OVERDOSE', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_poisoning_overdose = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_PREGNANT_ABDOMINAL_PAIN', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_pregnant_abdominal_pain = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_COUGHING_VOMITING_BLOOD', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_coughing_vomiting_blood = TRUE;

-- VU Diabetic high glucose carries vu_diabetic_glucose
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_DIABETIC_HIGH_GLUCOSE', 'MSAT_VU', 'PRESENT', t.vu_diabetic_glucose,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_diabetic_high_glucose = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_AGGRESSION', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_aggression = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_SHORTNESS_OF_BREATH', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_shortness_of_breath = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_BURN_OVER_20_PERCENT', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_burn_over_20_percent = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_OPEN_FRACTURE', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_open_fracture = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_THREATENED_LIMB', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_threatened_limb = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_EYE_INJURY', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_eye_injury = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_LARGE_JOINT_DISLOCATION', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_large_joint_dislocation = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_SEVERE_MECHANISM_OF_INJURY', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_severe_mechanism_of_injury = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_VERY_SEVERE_PAIN', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_very_severe_pain = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_VU_PREGNANT_ABDOMINAL_TRAUMA', 'MSAT_VU', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.vu_pregnant_abdominal_trauma = TRUE;

-- ── mSAT Urgent (Section 4) ──
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_UNABLE_TO_DRINK_VOMITS', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_unable_to_drink_vomits = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_ABDOMINAL_PAIN', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_abdominal_pain = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_VERY_PALE', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_very_pale = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_PREGNANT_VAGINAL_BLEEDING', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_pregnant_vaginal_bleeding = TRUE;

-- URG Diabetic very high glucose carries urg_diabetic_glucose
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_DIABETIC_VERY_HIGH_GLUCOSE', 'MSAT_URG', 'PRESENT', t.urg_diabetic_glucose,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_diabetic_very_high_glucose = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_FINGER_TOE_DISLOCATION', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_finger_toe_dislocation = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_CLOSED_FRACTURE', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_closed_fracture = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_BURN_WITHOUT_URGENT_SIGNS', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_burn_without_urgent_signs = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_PREGNANT_TRAUMA_NON_ABDOMINAL', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_pregnant_trauma_non_abdominal = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_MODERATE_PAIN', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_moderate_pain = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_LACERATION_ABSCESS', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_laceration_abscess = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'MSAT_URG_FOREIGN_BODY_ASPIRATION', 'MSAT_URG', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.urg_foreign_body_aspiration = TRUE;

-- ── Special considerations ──
INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'SPECIAL_ACUTE_TRAUMA', 'SPECIAL', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.special_acute_trauma = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'SPECIAL_SEIZURE_HISTORY', 'SPECIAL', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.special_seizure_history = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'SPECIAL_ASSAULT_ABUSE', 'SPECIAL', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.special_assault_abuse = TRUE;

INSERT INTO clinical_sign_events (id, created_at, updated_at, is_active, version, visit_id, patient_id, sign_code, sign_category, status, numeric_value, notes, recorded_at, recorded_by_name, is_baseline)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, t.visit_id, v.patient_id, 'SPECIAL_SUICIDE_ATTEMPT', 'SPECIAL', 'PRESENT', NULL,
       'Baseline backfilled from triage (V27)', t.triage_time, t.triage_nurse_name, TRUE
  FROM triage_records t JOIN visits v ON v.id = t.visit_id
 WHERE t.is_active = TRUE AND t.special_suicide_attempt = TRUE;
