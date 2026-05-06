-- V39 — Merge TRIAGE_NURSE role into NURSE.
--
-- Background: SmartTriage previously distinguished TRIAGE_NURSE from
-- NURSE at the role level. This conflated three distinct concepts:
--
--   Role        — system-access category (DOCTOR, NURSE, etc.)
--   Designation — clinical seniority (STAFF_NURSE, CHARGE_NURSE, ...)
--   Function    — per-shift assignment (handled by ShiftFunction +
--                 ShiftAssignment)
--
-- A "triage nurse" is a NURSE assigned to the triage station for a
-- given shift. That assignment lives in ShiftAssignment.shiftFunction
-- (which already has TRIAGE_NURSE as a value). Having it ALSO as a
-- role meant a hospital admin had to flip a user's role between
-- NURSE and TRIAGE_NURSE every rotation — operationally unworkable.
-- In practice nobody did this, and TRIAGE_NURSE became a permanent
-- "triage specialist" tag rather than the per-shift function it was
-- meant to represent.
--
-- This migration:
--   1. Converts every existing user with role = 'TRIAGE_NURSE' to
--      role = 'NURSE'. No data loss; the user can still be assigned
--      to triage via ShiftAssignment.shiftFunction = TRIAGE_NURSE.
--   2. Leaves Designation column unchanged. Users who had the
--      TRIAGE_NURSE role and a designation (e.g. STAFF_NURSE) keep
--      that designation — it's valid under NURSE per Designation.forRole.
--
-- The Java Role enum drops TRIAGE_NURSE in the same release; this
-- migration MUST run before that code deploys, otherwise existing
-- TRIAGE_NURSE users would fail to deserialize.
--
-- Idempotent: re-running is a no-op once all TRIAGE_NURSE rows have
-- been converted (the WHERE clause matches nothing the second time).

UPDATE users
SET role = 'NURSE'
WHERE role = 'TRIAGE_NURSE'
  AND is_active = true;

-- Soft-deleted (is_active = false) TRIAGE_NURSE users are also
-- converted so a future re-activation doesn't surface a role value
-- the application no longer recognises.
UPDATE users
SET role = 'NURSE'
WHERE role = 'TRIAGE_NURSE';
