-- ═══════════════════════════════════════════════════════════════
-- V66 — reconcile Magnesium Sulfate dose_unit (S2 follow-up).
--
-- BACKGROUND
-- ----------
-- V31 set magnesium sulfate's dose_unit='G' but did NOT change its stored
-- numeric values, which V30 seeded in mg / mg-per-kg:
--   adult_min_dose_mg = 1000, adult_max_dose_mg = 4000, adult_max_daily_dose_mg = 40000
--   pediatric_min_dose_mg_per_kg = 25, pediatric_max_dose_mg_per_kg = 50,
--   pediatric_max_daily_dose_mg_per_kg = 100
-- So the column claimed 'G' while the numbers were mg — internally inconsistent.
--
-- Because the MedicationSafetyEngine (S2) skips the numeric dose-range check for
-- any non-MG unit, magnesium — a HIGH-ALERT drug whose overdose causes
-- respiratory depression / arrest — was left with NO dose-range validation.
--
-- WHY 'MG' (not 'G')
-- ------------------
-- A single dose_unit column cannot be 'G' here without breaking the paediatric
-- mg/kg columns (they would be read as g/kg). The stored mg values are already
-- clinically correct, so the safe reconciliation is to label the unit 'MG':
--   adult:     1 g min / 4 g max single / 40 g max daily   (1000 / 4000 / 40000 mg)
--   paediatric: 25–50 mg/kg, 100 mg/kg/day
--
-- These match established dosing (verified against the literature):
--   - Eclampsia/pre-eclampsia: 4 g IV loading; 1–2 g/h maintenance (Zuspan);
--     Pritchard regimen ≈ 4 g IV + 10 g IM loading then 5 g IM q4h (~40 g/day).
--     (WHO recommends MgSO4 as first-line for severe pre-eclampsia/eclampsia.)
--   - Paediatric severe asthma: 25–75 mg/kg IV, maximum 2 g single dose.
--
-- EFFECT
-- ------
-- Restores correct mg-based dose-range checking for magnesium. NO clinical
-- values change — only the unit label. The frontend will render magnesium
-- amounts in mg (e.g. "4000 mg") rather than "4 g"; that is cosmetic and a
-- display-only gram format can be added later if desired.
--
-- LONG-TERM (out of scope): the proper fix is SEPARATE adult/paediatric
-- dose-unit columns, so a drug can be grams (adult, absolute) and mg/kg
-- (paediatric) at the same time. Until then, 'MG' is the correct, safe label
-- for magnesium because its stored values are already in mg / mg-per-kg.
-- ═══════════════════════════════════════════════════════════════

UPDATE drug_formularies
   SET dose_unit = 'MG'
 WHERE LOWER(generic_name) = 'magnesium sulfate'
   AND hospital_id IS NULL
   AND is_active = TRUE;
