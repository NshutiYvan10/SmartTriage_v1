-- ═══════════════════════════════════════════════════════════════
-- V26 — `dose_unit` column on drug_formularies + targeted backfill.
--
-- BACKGROUND
-- ----------
-- The drug formulary's dose ranges (adult_min_dose_mg, adult_max_dose_mg,
-- pediatric_*_mg_per_kg) are all numeric mg. But for several real REML
-- drugs, "mg" is the wrong unit:
--   - Insulin is dosed in international UNITS, not mg.
--   - Oxytocin is dosed in IU.
--   - Misoprostol is conventionally written in MICROGRAMS (200 mcg, 600 mcg)
--     even though we store it as 200 mg in V21 — the V21 numbers are wrong
--     by a factor of 1000. Fixing.
--   - Magnesium Sulfate emergency dosing in eclampsia is 4 g loading then
--     1 g/h — better to store as G, not mg.
--   - Dextrose 50% is volume (ml of a 50% solution), not mg.
--   - ORS is dosed in sachets dissolved in volume, not mg.
--
-- Without a dose_unit, the medication safety engine's numeric dose check
-- either (a) fails-closed by skipping these drugs entirely, leaving them
-- without dose validation, or (b) compares numbers in incompatible units
-- and produces nonsense warnings. Both are silent-failure modes.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- 1. Adds `dose_unit VARCHAR(20)` to drug_formularies, default 'MG'.
-- 2. Backfills existing rows: every drug that has numeric mg dose ranges
--    keeps unit = 'MG'. Specific drugs we know need different units are
--    updated in targeted UPDATE statements below.
--
-- The frontend prescribe panel uses the unit to:
--   - render the right unit suffix in dose hints ("4 g", not "4 mg")
--   - parse the doctor's free-text dose value with the correct unit
--   - skip mg-numeric validation for non-mg drugs (allergy + interaction
--     checks still run; dose-range validation simply isn't applicable
--     to "1 sachet" or "20 IU" without a per-drug conversion)
--
-- DOSE-RANGE FIX FOR MISOPROSTOL
-- -------------------------------
-- V21 seeded misoprostol as 200..600 (mg). The clinical dose is 200..600
-- MICROGRAMS. Setting dose_unit='MCG' fixes the unit but the numeric values
-- 200 / 600 are correct numbers — they just refer to mcg, not mg. We do
-- NOT divide by 1000 because the numbers themselves are right when read
-- with the correct unit.
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE drug_formularies
    ADD COLUMN IF NOT EXISTS dose_unit VARCHAR(20) NOT NULL DEFAULT 'MG';

-- Sanity check: every active row already starts as MG via the default.
-- Targeted overrides for drugs whose unit is genuinely not mg:

UPDATE drug_formularies SET dose_unit = 'UNITS'
 WHERE LOWER(generic_name) IN ('insulin regular (soluble)', 'insulin isophane (nph)')
   AND hospital_id IS NULL AND is_active = TRUE;

UPDATE drug_formularies SET dose_unit = 'IU'
 WHERE LOWER(generic_name) = 'oxytocin'
   AND hospital_id IS NULL AND is_active = TRUE;

UPDATE drug_formularies SET dose_unit = 'MCG'
 WHERE LOWER(generic_name) = 'misoprostol'
   AND hospital_id IS NULL AND is_active = TRUE;

UPDATE drug_formularies SET dose_unit = 'G'
 WHERE LOWER(generic_name) = 'magnesium sulfate'
   AND hospital_id IS NULL AND is_active = TRUE;

UPDATE drug_formularies SET dose_unit = 'ML'
 WHERE LOWER(generic_name) = 'dextrose 50%'
   AND hospital_id IS NULL AND is_active = TRUE;

UPDATE drug_formularies SET dose_unit = 'SACHETS'
 WHERE LOWER(generic_name) = 'oral rehydration salts'
   AND hospital_id IS NULL AND is_active = TRUE;

COMMENT ON COLUMN drug_formularies.dose_unit IS
    'Unit for adult_*_dose_mg / pediatric_*_dose_mg_per_kg. Despite the column names referencing "mg", the numeric values are interpreted in this unit. Allowed values: MG, MCG, G, UNITS, IU, ML, SACHETS, TABLETS, PUFFS, DROPS.';
