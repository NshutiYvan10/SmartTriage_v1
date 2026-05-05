-- V26: Numeric investigation results + adult weight on vitals.
--
-- Background — Phase 12b (full eGFR via Cockcroft-Gault):
--   Phase 12a shipped a screening-level renal-risk check that fired
--   on text mentions of CKD or AKI-pattern vitals. It cannot tell
--   the prescriber "this dose is too high for an eGFR of 28" because
--   we don't have:
--     • A creatinine value as a number (current `result` is free-text
--       like "Cr 1.8 mg/dL — moderately elevated").
--     • A reliable adult weight (paediatric weight is captured at
--       triage; adults are not).
--
--   Cockcroft-Gault eGFR = ((140 - age) × weightKg × (0.85 if female))
--                          / (72 × serumCreatinine_mgPerDl)
--
--   It needs all four: age (deterministic from DOB), sex (deterministic
--   from gender), weight, serum creatinine. This migration unblocks
--   the last two.
--
-- ── Investigations: numeric result + unit ────────────────────────
--
-- We keep the existing free-text `result` column — it captures the
-- nuance ("trace haemolysis, repeat sent") that any single number
-- can't. The new columns are additive:
--   • result_numeric  : the principal scalar value (creatinine,
--                       haemoglobin, K+, etc.) for downstream
--                       calculation.
--   • result_unit     : the unit string ("mg/dL", "µmol/L", "mmol/L",
--                       "g/dL"). Stored as a free-text VARCHAR
--                       rather than an enum because lab units vary
--                       by hospital — Rwanda and many EU hospitals
--                       report creatinine in µmol/L; US sites in
--                       mg/dL. eGFRCalc on the frontend handles both.
--
-- Both nullable so existing rows are valid without backfill.
--
-- Stored as DOUBLE PRECISION (Hibernate `Double`) — float4 would
-- lose precision on µmol/L creatinine values that span 30–800.
--
-- ── Vitals: adult weight ─────────────────────────────────────────
--
-- The paediatric `child_weight_kg` lives on `triage_records` because
-- weight is captured at the paediatric triage form. Adults skip
-- that form (the adult triage form does not include weight). We add
-- `weight_kg` directly on `vital_signs` so:
--   • Bed-side vitals capture can record it any time during the
--     visit (the standard pattern for adult inpatient too).
--   • The Cockcroft-Gault calc reads the latest vitals row that
--     has a non-NULL weight — same pattern as latest BP, latest HR.
--
-- DECIMAL(5,2) — supports up to 999.99 kg, two-decimal precision.
-- Nullable so vitals rows without weight (most of them, in the
-- adult flow) remain valid.

ALTER TABLE investigations
    ADD COLUMN result_numeric DOUBLE PRECISION;

ALTER TABLE investigations
    ADD COLUMN result_unit VARCHAR(32);

ALTER TABLE vital_signs
    ADD COLUMN weight_kg DECIMAL(5,2);

COMMENT ON COLUMN investigations.result_numeric IS
    'Principal scalar value of the investigation result. Optional — '
    'paired with result_unit. Lets downstream calculators (eGFR, '
    'sepsis bundle scoring) read a number without parsing free text.';

COMMENT ON COLUMN investigations.result_unit IS
    'Unit string ("mg/dL", "µmol/L", "mmol/L", etc). Free text — '
    'lab units vary by site. Frontend converters interpret as needed.';

COMMENT ON COLUMN vital_signs.weight_kg IS
    'Adult body weight in kilograms. Used by Cockcroft-Gault eGFR '
    'and any other weight-based dosing for adults. Paediatric weight '
    'is on triage_records.child_weight_kg.';
