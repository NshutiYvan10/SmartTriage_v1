-- V113: best-estimate gestational age (completed weeks) on the patient (Phase 13c).
--
-- Pregnancy status (V25) says WHETHER; gestational age says HOW FAR ALONG — which
-- changes management: the teratogen gate escalates late-pregnancy-risk drugs
-- (NSAIDs, category D-late) to a hard block once the patient is in the 3rd
-- trimester, and triage priority for obstetric presentations is gestation-aware.
-- Captured at triage alongside pregnancy status. Nullable; only meaningful for a
-- pregnant / possibly-pregnant patient.

ALTER TABLE patients ADD COLUMN gestational_age_weeks INTEGER;
