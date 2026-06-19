-- V91: support national (cross-hospital) MoH reports.
--
-- A national report aggregates de-identified statistics across ALL active hospitals
-- for a period. It has no single owning hospital, so hospital_id becomes nullable and
-- a report_level column distinguishes HOSPITAL (default) from NATIONAL rollups.
-- included_hospital_count records how many hospitals a national rollup spanned.

ALTER TABLE moh_reports ALTER COLUMN hospital_id DROP NOT NULL;

ALTER TABLE moh_reports ADD COLUMN report_level VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL';

ALTER TABLE moh_reports ADD COLUMN included_hospital_count INTEGER;

-- Existing rows are all single-hospital reports; the DEFAULT already set them to HOSPITAL.
CREATE INDEX idx_moh_report_level_period ON moh_reports (report_level, report_period_start);
