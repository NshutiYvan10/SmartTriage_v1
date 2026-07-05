-- V106 — preserve the original unit of a hypoglycemia repeat-glucose recheck.
--
-- recordRepeatGlucose stores only the converted mmol/L value, so a reviewer
-- can no longer tell whether the clinician entered e.g. "100 mg/dL" or a raw
-- mmol/L figure — an audit-completeness gap on a life-critical recheck. Persist
-- the entered unit alongside the (still canonical) mmol/L value so the chart can
-- show "100 mg/dL (5.6 mmol/L)". Nullable: pre-V106 rows predate the column, and
-- the value stays classified/compared in mmol/L regardless.
ALTER TABLE hypoglycemia_events ADD COLUMN repeat_glucose_unit VARCHAR(10);
