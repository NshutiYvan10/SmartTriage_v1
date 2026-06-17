-- V81 — Catalog-driven reference ranges, critical (panic) thresholds and the
-- canonical result unit per lab test.
--
-- WHY: critical-value detection was hardcoded in CriticalValueEngine for ~12
-- analytes and IGNORED units entirely (e.g. a glucose entered in mg/dL was
-- compared against mmol/L thresholds — a real mis-flag / missed-critical hazard).
-- These columns let the engine evaluate against per-test thresholds in a KNOWN
-- unit, fall back to catalog reference ranges for the abnormal flag, and let an
-- admin tune/extend thresholds without a code change.
--
-- SCOPE / HONESTY:
--  * Values below are STANDARD ADULT references in SI units and REQUIRE local
--    clinician/lab sign-off before clinical reliance. Pediatric / sex-specific
--    banding is deliberately deferred (needs a banded model, not single columns).
--  * Only SINGLE-ANALYTE tests get critical thresholds. PANEL tests (FBC, U&E,
--    LFT, Lipid) are left NULL: a panel yields many analytes but a LabOrder stores
--    one numeric result, so a single per-row threshold is not meaningful. Per-
--    analyte detection inside a panel requires a multi-analyte results model
--    (tracked as a separate, larger piece of work).

ALTER TABLE lab_test_catalog ADD COLUMN IF NOT EXISTS result_unit     VARCHAR(30);
ALTER TABLE lab_test_catalog ADD COLUMN IF NOT EXISTS reference_low   DOUBLE PRECISION;
ALTER TABLE lab_test_catalog ADD COLUMN IF NOT EXISTS reference_high  DOUBLE PRECISION;
ALTER TABLE lab_test_catalog ADD COLUMN IF NOT EXISTS critical_low    DOUBLE PRECISION;
ALTER TABLE lab_test_catalog ADD COLUMN IF NOT EXISTS critical_high   DOUBLE PRECISION;

-- ── Single-analyte tests: unit + reference range + critical (panic) thresholds ──
-- Glucose (mmol/L): severe hypo <2.5, severe hyper >25 (DKA/HHS territory).
UPDATE lab_test_catalog SET result_unit = 'mmol/L', reference_low = 3.9, reference_high = 7.8,
       critical_low = 2.5, critical_high = 25.0 WHERE test_name = 'Random Blood Glucose';
UPDATE lab_test_catalog SET result_unit = 'mmol/L', reference_low = 3.9, reference_high = 5.5,
       critical_low = 2.5, critical_high = 25.0 WHERE test_name = 'Fasting Blood Glucose';

-- Creatinine (µmol/L): severe renal failure panic. (NB: SI units — the old code
-- used mg/dL >10 ≈ 884 µmol/L; 500 µmol/L is a conservative ED panic value.)
UPDATE lab_test_catalog SET result_unit = 'µmol/L', reference_low = 60, reference_high = 110,
       critical_high = 500 WHERE test_name = 'Creatinine';

-- Lactate (mmol/L): >4.0 = severe sepsis / shock indicator.
UPDATE lab_test_catalog SET result_unit = 'mmol/L', reference_low = 0.5, reference_high = 2.0,
       critical_high = 4.0 WHERE test_name = 'Lactate';

-- NB: Coagulation Profile (PT/INR/aPTT) and Arterial/Venous Blood Gas are PANELS
-- (the blood gas yields pH, pCO2 AND pO2; the most rapidly lethal value — a low pO2 —
-- cannot be represented by a single pH threshold). Like FBC/U&E/LFT they are left with
-- NO thresholds here: binding one threshold/unit to a panel row would mis-evaluate a
-- different analyte's number (e.g. a pO2 in kPa compared against pH 7.2). Per-analyte
-- detection for these needs the multi-analyte results model. The engine's keyword rules
-- still catch a genuinely INR- or pH-typed result when the unit is explicitly given.

-- ── Single-analyte tests: reference range + unit only (abnormal-flag + pre-fill;
--    not life-threatening "panic" values, so no critical thresholds) ──
UPDATE lab_test_catalog SET result_unit = 'mg/L',   reference_low = 0,   reference_high = 5    WHERE test_name = 'C-Reactive Protein';
UPDATE lab_test_catalog SET result_unit = 'ng/mL',  reference_low = 0,   reference_high = 0.5  WHERE test_name = 'Procalcitonin';
UPDATE lab_test_catalog SET result_unit = 'µg/mL',  reference_low = 0,   reference_high = 0.5  WHERE test_name = 'D-dimer';
UPDATE lab_test_catalog SET result_unit = '%',      reference_low = 4.0, reference_high = 5.6  WHERE test_name = 'HbA1c';
UPDATE lab_test_catalog SET result_unit = 'mm/hr',  reference_low = 0,   reference_high = 20   WHERE test_name = 'Erythrocyte Sedimentation Rate';
