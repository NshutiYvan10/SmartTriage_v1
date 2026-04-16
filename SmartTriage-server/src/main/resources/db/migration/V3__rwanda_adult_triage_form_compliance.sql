-- =====================================================================
-- V3: Rwanda National Standard Adult Triage Form — Full Compliance Schema Update
--
-- Aligns triage_records table with the Rwanda National Standard
-- Adult Triage Form (mSAT protocol) — every checkbox and data field.
--
-- Changes:
--   1. Remove old generic emergency sign columns
--   2. Add granular Emergency Signs (Section 1 of standard form)
--   3. Add Very Urgent Signs — Medical & Trauma (Section 2)
--   4. Add Urgent Signs (Section 3)
--   5. Add Special Considerations
--   6. Add decision path audit trail
--   7. Add triage form footer fields (nurse, doctor notification)
-- =====================================================================

-- ====================================================================
-- DROP OLD COLUMNS that don't match the standard form
-- ====================================================================
ALTER TABLE triage_records DROP COLUMN IF EXISTS has_circulation_compromise;
ALTER TABLE triage_records DROP COLUMN IF EXISTS has_severe_dehydration;

-- ====================================================================
-- SECTION 1: EMERGENCY SIGNS — Granular standard form fields
-- ====================================================================

-- Severe respiratory distress (separate from airway compromise)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_severe_respiratory_distress BOOLEAN NOT NULL DEFAULT FALSE;

-- Circulation: Cardiac arrest
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_cardiac_arrest BOOLEAN NOT NULL DEFAULT FALSE;

-- Circulation: Haemorrhage – uncontrolled
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_uncontrolled_haemorrhage BOOLEAN NOT NULL DEFAULT FALSE;

-- Circulation: Stab/gunshot wound to neck or chest
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_stab_gun_wound_neck_chest BOOLEAN NOT NULL DEFAULT FALSE;

-- Convulsions: Associated glucose reading (mmol/L)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS convulsion_glucose DOUBLE PRECISION;

-- Coma: Associated glucose reading (mmol/L)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS coma_glucose DOUBLE PRECISION;

-- Other: Hypoglycaemia (Glucose < 3 mmol/L or 60 mg/dL)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_hypoglycaemia BOOLEAN NOT NULL DEFAULT FALSE;

-- Other: Purpuric rash
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_purpuric_rash BOOLEAN NOT NULL DEFAULT FALSE;

-- Other: Burn – face/inhalation
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS has_burn_face_inhalation BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- SECTION 2: VERY URGENT SIGNS — Medical
-- ====================================================================

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_focal_neurologic_deficit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_altered_mental_status BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_neurological_glucose DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_chest_pain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_poisoning_overdose BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_pregnant_abdominal_pain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_coughing_vomiting_blood BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_diabetic_high_glucose BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_diabetic_glucose DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_aggression BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_shortness_of_breath BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- SECTION 2: VERY URGENT SIGNS — Trauma
-- ====================================================================

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_burn_over_20_percent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_open_fracture BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_threatened_limb BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_eye_injury BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_large_joint_dislocation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_severe_mechanism_of_injury BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_very_severe_pain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS vu_pregnant_abdominal_trauma BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- SECTION 3: URGENT SIGNS
-- ====================================================================

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_unable_to_drink_vomits BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_abdominal_pain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_very_pale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_pregnant_vaginal_bleeding BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_diabetic_very_high_glucose BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_diabetic_glucose DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_finger_toe_dislocation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_closed_fracture BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_burn_without_urgent_signs BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_pregnant_trauma_non_abdominal BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_moderate_pain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_laceration_abscess BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS urg_foreign_body_aspiration BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- DECISION ENGINE AUDIT TRAIL
-- ====================================================================

-- The decision path explains which branch of the Rwanda triage flowchart was followed
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS decision_path TEXT;

-- ====================================================================
-- SPECIAL CONSIDERATIONS (bottom of standard form)
-- ====================================================================

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS special_acute_trauma BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS special_seizure_history BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS special_assault_abuse BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS special_suicide_attempt BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- TRIAGE FORM FOOTER FIELDS
-- ====================================================================

-- "Nurse: _____ Triage finish time: _____"
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS triage_nurse_name VARCHAR(255);

-- "For RED/ORANGE: Dr. _____ notified at: _____"
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS notified_doctor_name VARCHAR(255);
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS doctor_notified_at TIMESTAMPTZ;

-- "Dr. _____ Attended at: _____"
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS attending_doctor_name VARCHAR(255);
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS doctor_attended_at TIMESTAMPTZ;
