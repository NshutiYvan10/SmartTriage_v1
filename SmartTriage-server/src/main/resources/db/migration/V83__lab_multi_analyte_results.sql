-- V83 — Multi-analyte (panel) results model.
--
-- WHY: a LabOrder stores ONE numeric result, but most ED panels (FBC, U&E, LFT, blood
-- gas, coagulation) yield MANY analytes. That single-result model is why a critical K+
-- inside a U&E, a low Hb inside an FBC, or a low pO2 inside a blood gas can NEVER be
-- auto-detected today (the Tier-1 review flagged exactly this). This adds:
--   * lab_panel_component  — reference definition of which analytes a panel contains,
--                            with per-analyte unit + reference range + critical thresholds.
--   * lab_result_component — the actual per-analyte values a tech enters for an order,
--                            each independently abnormal/critical-flagged.
--
-- The order keeps its single result fields for genuinely single-analyte tests; for a
-- panel the components carry the values and the order's isCritical rolls up from them.
--
-- CLINICAL DATA: the seeded panel definitions are STANDARD ADULT references in SI units
-- and REQUIRE local clinician/lab sign-off before clinical reliance. Pediatric / sex
-- banding is deferred (needs a banded model). Panic thresholds are conservative ED values.

-- ── Reference: analyte composition of each panel ──
CREATE TABLE IF NOT EXISTS lab_panel_component (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    version          BIGINT NOT NULL DEFAULT 0,
    panel_test_name  VARCHAR(200) NOT NULL,   -- matches lab_test_catalog.test_name
    analyte_name     VARCHAR(120) NOT NULL,
    analyte_code     VARCHAR(40),
    result_unit      VARCHAR(30),
    reference_low    DOUBLE PRECISION,
    reference_high   DOUBLE PRECISION,
    critical_low     DOUBLE PRECISION,
    critical_high    DOUBLE PRECISION,
    display_order    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_panel_component_panel ON lab_panel_component (panel_test_name);

-- ── Actual per-analyte results for an order ──
CREATE TABLE IF NOT EXISTS lab_result_component (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(255),
    last_modified_by    VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    version             BIGINT NOT NULL DEFAULT 0,
    lab_order_id        UUID NOT NULL REFERENCES lab_orders(id),
    analyte_name        VARCHAR(120) NOT NULL,
    analyte_code        VARCHAR(40),
    result_value        VARCHAR(255),
    result_numeric      DOUBLE PRECISION,
    result_unit         VARCHAR(30),
    reference_low       DOUBLE PRECISION,
    reference_high      DOUBLE PRECISION,
    is_abnormal         BOOLEAN NOT NULL DEFAULT FALSE,
    is_critical         BOOLEAN NOT NULL DEFAULT FALSE,
    critical_value_type VARCHAR(40),
    display_order       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_result_component_order ON lab_result_component (lab_order_id);

-- ── Seed panel compositions (adult SI; LOCAL CLINICIAN SIGN-OFF REQUIRED) ──
INSERT INTO lab_panel_component
    (id, created_at, is_active, panel_test_name, analyte_name, analyte_code, result_unit,
     reference_low, reference_high, critical_low, critical_high, display_order) VALUES
-- Full Blood Count
(gen_random_uuid(), now(), TRUE, 'Full Blood Count', 'Hemoglobin',       'HGB', 'g/dL',     12,  16,   5,    NULL, 1),
(gen_random_uuid(), now(), TRUE, 'Full Blood Count', 'White Cell Count', 'WBC', 'x10^9/L',   4,   11,   1.0,  30,   2),
(gen_random_uuid(), now(), TRUE, 'Full Blood Count', 'Platelets',        'PLT', 'x10^9/L',   150, 400,  20,   NULL, 3),
(gen_random_uuid(), now(), TRUE, 'Full Blood Count', 'Hematocrit',       'HCT', '%',         36,  50,   NULL, NULL, 4),
-- Urea and Electrolytes
(gen_random_uuid(), now(), TRUE, 'Urea and Electrolytes', 'Sodium',     'NA',  'mmol/L', 135, 145, 120, 160,  1),
(gen_random_uuid(), now(), TRUE, 'Urea and Electrolytes', 'Potassium',  'K',   'mmol/L', 3.5, 5.0, 2.5, 6.0,  2),
(gen_random_uuid(), now(), TRUE, 'Urea and Electrolytes', 'Urea',       'UREA','mmol/L', 2.5, 7.1, NULL, NULL, 3),
(gen_random_uuid(), now(), TRUE, 'Urea and Electrolytes', 'Creatinine', 'CR',  'µmol/L', 60,  110, NULL, 500,  4),
-- Liver Function Tests
(gen_random_uuid(), now(), TRUE, 'Liver Function Tests', 'ALT',             'ALT', 'U/L',    7,  56,  NULL, NULL, 1),
(gen_random_uuid(), now(), TRUE, 'Liver Function Tests', 'AST',             'AST', 'U/L',    10, 40,  NULL, NULL, 2),
(gen_random_uuid(), now(), TRUE, 'Liver Function Tests', 'Total Bilirubin', 'TBIL','µmol/L', 3,  17,  NULL, 250,  3),
(gen_random_uuid(), now(), TRUE, 'Liver Function Tests', 'Alkaline Phosphatase', 'ALP','U/L', 44, 147, NULL, NULL, 4),
(gen_random_uuid(), now(), TRUE, 'Liver Function Tests', 'Albumin',         'ALB', 'g/L',    35, 50,  NULL, NULL, 5),
-- Coagulation Profile (PT/INR/aPTT)
(gen_random_uuid(), now(), TRUE, 'Coagulation Profile (PT/INR/aPTT)', 'INR',  'INR',  'INR', 0.8, 1.2, NULL, 5.0,  1),
(gen_random_uuid(), now(), TRUE, 'Coagulation Profile (PT/INR/aPTT)', 'PT',   'PT',   's',   11,  13.5, NULL, NULL, 2),
(gen_random_uuid(), now(), TRUE, 'Coagulation Profile (PT/INR/aPTT)', 'aPTT', 'APTT', 's',   25,  35,  NULL, NULL, 3),
-- Arterial Blood Gas (pO2 critical low — the value the single-result model could never catch)
(gen_random_uuid(), now(), TRUE, 'Arterial Blood Gas', 'pH',          'PH',    'pH',     7.35, 7.45, 7.2,  7.6,  1),
-- pCO2 critical-high catches acute hypercapnic respiratory failure / CO2 narcosis (~9.5 kPa ≈ 71 mmHg).
(gen_random_uuid(), now(), TRUE, 'Arterial Blood Gas', 'pCO2',        'PCO2',  'kPa',    4.7,  6.0,  NULL, 9.5,  2),
(gen_random_uuid(), now(), TRUE, 'Arterial Blood Gas', 'pO2',         'PO2',   'kPa',    11,   13,   8.0,  NULL, 3),
(gen_random_uuid(), now(), TRUE, 'Arterial Blood Gas', 'Bicarbonate', 'HCO3',  'mmol/L', 22,   26,   NULL, NULL, 4),
(gen_random_uuid(), now(), TRUE, 'Arterial Blood Gas', 'Lactate',     'LAC',   'mmol/L', 0.5,  2.0,  NULL, 4.0,  5),
-- Venous Blood Gas (acid-base + lactate; no pO2 emphasis)
(gen_random_uuid(), now(), TRUE, 'Venous Blood Gas', 'pH',          'PH',   'pH',     7.32, 7.43, 7.2,  7.6,  1),
(gen_random_uuid(), now(), TRUE, 'Venous Blood Gas', 'pCO2',        'PCO2', 'kPa',    5.0,  6.7,  NULL, 9.5,  2),
(gen_random_uuid(), now(), TRUE, 'Venous Blood Gas', 'Bicarbonate', 'HCO3', 'mmol/L', 22,   29,   NULL, NULL, 3),
(gen_random_uuid(), now(), TRUE, 'Venous Blood Gas', 'Lactate',     'LAC',  'mmol/L', 0.5,  2.0,  NULL, 4.0,  4);
