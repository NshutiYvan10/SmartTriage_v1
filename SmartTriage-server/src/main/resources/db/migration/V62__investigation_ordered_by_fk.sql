-- ============================================================
-- V62 — Investigation.ordered_by_id User FK
-- ============================================================
--
-- The Investigation row carried only a free-text `ordered_by_name`
-- column. That made the doctor's "show me all my pending /
-- in-progress / resulted orders" view impossible to drive
-- reliably — names typo, names change, two staff share a name.
--
-- This migration adds the User FK. Backend service stamps it on
-- create (resolving from the SecurityContext, mirroring the
-- MedicationService.prescribe pattern). Pre-existing rows have
-- NULL — the doctor-scoped query falls back to a case-insensitive
-- name match against the authenticated user's full name so
-- historical orders still surface in the new view.
-- ============================================================

ALTER TABLE investigations
    ADD COLUMN ordered_by_id UUID NULL REFERENCES users(id);

CREATE INDEX idx_investigation_ordered_by ON investigations(ordered_by_id);
