-- ═══════════════════════════════════════════════════════════════
-- V17: Tighten the role → designation coupling.
--
-- Two related issues this migration addresses:
--
-- 1. The NURSE_MANAGER designation is being removed from the Designation
--    enum. "Charge Nurse" already covers unit-management responsibilities,
--    so the separate manager tier is redundant and confusing. Any existing
--    user carrying this value must be rewritten before Hibernate boots,
--    because @Enumerated(EnumType.STRING) will throw
--    IllegalArgumentException on a value the enum no longer declares.
--
-- 2. Under the old backend, Designation.forRole() returned the same full
--    nurse ladder for both NURSE and TRIAGE_NURSE, so admins could set a
--    TRIAGE_NURSE user to CHARGE_NURSE. That's nonsensical — a triage
--    nurse is dedicated to intake assessment, not unit management.
--    Any such rows are also rewritten here so they pass validation in
--    future role/designation edits.
--
-- Both rewrites target STAFF_NURSE as the safe, ladder-neutral fallback
-- for affected users. SENIOR_NURSE would be a plausible alternative but
-- would grant an unearned seniority bump; STAFF_NURSE is conservative.
-- Admins can promote individuals back up through the UI after migration.
-- ═══════════════════════════════════════════════════════════════

-- 1. Any user currently stamped NURSE_MANAGER → STAFF_NURSE.
--    Existing seed data does not use this value, so on most environments
--    this UPDATE affects zero rows. It's present for environments that
--    accepted the designation via the API before this patch.
UPDATE users
   SET designation = 'STAFF_NURSE'
 WHERE designation = 'NURSE_MANAGER';

-- 2. Any TRIAGE_NURSE user currently stamped CHARGE_NURSE → STAFF_NURSE.
--    A charge nurse title implies unit-management authority that the
--    TRIAGE_NURSE role does not carry; the combination was only possible
--    because of the old permissive forRole() mapping.
UPDATE users
   SET designation = 'STAFF_NURSE'
 WHERE role = 'TRIAGE_NURSE'
   AND designation = 'CHARGE_NURSE';
