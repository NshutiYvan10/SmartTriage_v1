-- V8: Add additional vitals columns to triage_records
-- These are clinical measurements recorded during triage but NOT used in TEWS scoring.
-- SpO2, Diastolic BP, Blood Glucose, Pain Score, Weight, Height.

ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS spo2 INTEGER;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS diastolic_bp INTEGER;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS blood_glucose DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS pain_score INTEGER;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS weight_kg DOUBLE PRECISION;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS height_cm DOUBLE PRECISION;

COMMENT ON COLUMN triage_records.spo2 IS 'Oxygen saturation (SpO2) percentage';
COMMENT ON COLUMN triage_records.diastolic_bp IS 'Diastolic blood pressure in mmHg';
COMMENT ON COLUMN triage_records.blood_glucose IS 'Blood glucose in mmol/L';
COMMENT ON COLUMN triage_records.pain_score IS 'Pain score 0-10 (Numeric Rating Scale)';
COMMENT ON COLUMN triage_records.weight_kg IS 'Patient weight in kilograms';
COMMENT ON COLUMN triage_records.height_cm IS 'Patient height in centimetres';
