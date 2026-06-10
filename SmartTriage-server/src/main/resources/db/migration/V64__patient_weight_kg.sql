-- ═══════════════════════════════════════════════════════════════
-- V64 — patient body-weight column (S8).
--
-- BACKGROUND
-- ----------
-- Weight previously lived only on:
--   - vital_signs.weight_kg          (adult, Phase 12b eGFR dosing)
--   - triage_records.child_weight_kg (paediatric, captured at triage)
-- There was no durable weight on the patient captured at REGISTRATION,
-- so a weight-based paediatric dose reference had no source before the
-- triage step happened.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- Adds a nullable weight_kg column to patients, optionally captured at
-- registration. NUMERIC(5,2) mirrors vital_signs.weight_kg (max 999.99 kg,
-- two decimal places).
--
-- INTENTIONAL SCOPE NOTE
-- ----------------------
-- This column is ADDITIVE DATA CAPTURE only. It is deliberately NOT wired
-- into the automatic medication dose-range check. That check uses the
-- per-visit triage weight on purpose: a dose warning that hinges on a
-- stale registration weight is worse than no warning. Surfacing this value
-- for display, and letting a clinician confirm it before any dosing use,
-- is a separate follow-up (would need a recorded-at timestamp + freshness
-- guard).
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(5,2);

COMMENT ON COLUMN patients.weight_kg IS
    'Optional patient body weight in kg, captured at registration (S8). Additive data only — NOT consumed by the automatic medication dose-range check (which uses the per-visit triage weight to avoid acting on a stale value).';
