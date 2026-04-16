-- =====================================================================
-- V4: Rwanda National Standard Child Triage Form (3-12 years) — Schema Update
--
-- Adds child-specific emergency sign columns to triage_records table.
-- These fields are ONLY populated for pediatric patients (3-12 years).
-- They correspond to checkboxes on the Child Triage Form that differ
-- from the Adult Triage Form.
--
-- The Very Urgent / Urgent sign columns (back of form) are shared
-- and already exist from V3.
--
-- Changes:
--   1. Add form type indicator (is_child_form)
--   2. Add child-specific emergency signs (central cyanosis, pulse low,
--      cold hands composite, severe dehydration)
--   3. Add child form footer measurements (weight, height)
-- =====================================================================

-- ====================================================================
-- FORM TYPE INDICATOR
-- ====================================================================
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS is_child_form BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- CHILD-SPECIFIC EMERGENCY SIGNS — Airway / Breathing
-- ====================================================================

-- Central cyanosis (child form only — not on adult form)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_central_cyanosis BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- CHILD-SPECIFIC EMERGENCY SIGNS — Circulation
-- ====================================================================

-- Pulse low or absent (child form only)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_pulse_low_or_absent BOOLEAN NOT NULL DEFAULT FALSE;

-- Cold hands PLUS ≥1 of: lethargic, pulse weak/fast, cap refill ≥ 3s
-- (child form composite sign)
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_cold_hands_composite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_cold_hands_lethargic BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_cold_hands_pulse_weak_fast BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_cold_hands_cap_refill BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- CHILD-SPECIFIC EMERGENCY SIGNS — Dehydration
-- (entire section absent from adult form)
-- ====================================================================

-- Severe dehydration ≥ +2 of: Skin pinch ≥ 2 sec, Lethargy, Sunken eyes
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_severe_dehydration BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_dehydration_skin_pinch BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_dehydration_lethargy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_dehydration_sunken_eyes BOOLEAN NOT NULL DEFAULT FALSE;

-- ====================================================================
-- CHILD FORM FOOTER MEASUREMENTS
-- (BP:__/__ Weight:__ Height:__ — not scored in TEWS but recorded)
-- ====================================================================

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_weight_kg DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS child_height_cm DOUBLE PRECISION;
