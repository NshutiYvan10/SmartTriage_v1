-- V76 — Hypoglycemia recheck enforcement + action trail + source/neonatal
--
-- The hypoglycemia audit found: no enforced 15-minute recheck (recheck_due_at +
-- a scheduled monitor close that gap), a client-supplied/weak action trail
-- (detected_by/resolved_by now from the authenticated principal), no record of
-- WHICH glucose source fired detection (glucose_source), and no neonatal banding
-- flag (is_neonatal). treatment_given_by_name already exists from the original schema.

ALTER TABLE hypoglycemia_events
    ADD COLUMN glucose_source     VARCHAR(30),
    ADD COLUMN is_neonatal        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN detected_by_name   VARCHAR(255),
    ADD COLUMN recheck_due_at     TIMESTAMPTZ,
    ADD COLUMN resolved_by_name   VARCHAR(255);
