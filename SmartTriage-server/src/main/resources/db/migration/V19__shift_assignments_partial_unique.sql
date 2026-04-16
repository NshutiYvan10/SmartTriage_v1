-- ═══════════════════════════════════════════════════════════════
-- V19 — Shift assignments: partial unique on (user, date, period)
--
-- BACKGROUND
-- ----------
-- V9 created:
--   CONSTRAINT uq_shift_user_date_period UNIQUE (user_id, shift_date, shift_period)
--
-- This is a FULL unique constraint — it counts every row, including those
-- that have been soft-deleted (`is_active = false`).
--
-- ShiftAssignmentService.assignToZone() uses a deactivate-then-insert
-- pattern to support reassigning a staff member to a different zone during
-- the same shift:
--
--   1. Find the existing active row for (user, date, period).
--   2. Flip is_active = false and stamp endedAt (audit trail).
--   3. Insert a new row with the new zone/function.
--
-- Step 3 fails under the full constraint with:
--   ERROR: duplicate key value violates unique constraint
--   "uq_shift_user_date_period"
--
-- The charge nurse sees "An unexpected error occurred. Contact system
-- administrator." — which for a life-critical ED scheduling tool is an
-- unacceptable degradation of the workflow.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- Drops the full unique constraint and replaces it with a PARTIAL unique
-- INDEX that only applies where `is_active = true`. This matches the
-- pattern already used in V18 for beds (`uk_bed_one_active_visit`,
-- `uk_visit_one_bed`, `uk_device_one_bed`) and is the standard way to
-- enforce "at most one live row" alongside a soft-delete audit trail.
--
-- BUSINESS RULE PRESERVED
-- -----------------------
-- A staff member still cannot hold two ACTIVE assignments for the same
-- shift period on the same date — the partial unique index enforces that.
-- Soft-deleted rows are audit history and may accumulate freely.
--
-- SAFETY
-- ------
-- Dropping the constraint cannot produce data loss: the partial unique
-- index is created in the same migration and enforces the same invariant
-- for live rows. If any row violates the new partial constraint (i.e. a
-- duplicate *active* assignment exists from a prior inconsistent state),
-- the index creation itself will fail and the migration will abort before
-- the drop is committed — Flyway runs the whole file in a single
-- transaction on PostgreSQL.
-- ═══════════════════════════════════════════════════════════════

-- 1. Drop the too-strict full unique constraint.
ALTER TABLE shift_assignments
    DROP CONSTRAINT IF EXISTS uq_shift_user_date_period;

-- 2. Re-enforce the rule only for active rows.
CREATE UNIQUE INDEX IF NOT EXISTS uk_shift_user_date_period_active
    ON shift_assignments (user_id, shift_date, shift_period)
    WHERE is_active = true;

-- 3. Support the deactivate-then-insert lookup path so the existing query
--    `findByUserIdAndShiftDateAndShiftPeriodAndIsActiveTrue` stays fast as
--    the audit trail grows.
CREATE INDEX IF NOT EXISTS idx_shift_user_date_period_active
    ON shift_assignments (user_id, shift_date, shift_period)
    WHERE is_active = true;
