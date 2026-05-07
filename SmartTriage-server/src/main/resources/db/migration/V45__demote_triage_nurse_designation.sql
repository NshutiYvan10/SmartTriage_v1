-- ════════════════════════════════════════════════════════════════════════
-- V45 — Demote `Designation.TRIAGE_NURSE` to `STAFF_NURSE`.
--
-- Background: V29 collapsed TRIAGE_NURSE the *Role* into NURSE, then a
-- subsequent commit re-introduced TRIAGE_NURSE as a *Designation* under
-- Role.NURSE. Operational reality on Rwandan ED floors is that triage is
-- a per-shift station, not a long-term identity — a nurse may staff
-- ACUTE today and TRIAGE tomorrow. The cleaner model is therefore:
--
--   Role         — system-access category (NURSE, DOCTOR, …)
--   Designation  — seniority + permanent management authority
--                  (CHARGE_NURSE / SENIOR / STAFF / STUDENT)
--   ShiftFunction — what you do TODAY (already has TRIAGE_NURSE,
--                  CHARGE_NURSE, ZONE_NURSE, PRIMARY_DOCTOR, …)
--
-- Triage is a ShiftFunction, full stop. The Java enum will no longer
-- contain Designation.TRIAGE_NURSE after this release; un-migrated rows
-- would fail JPA hydration on app start.
--
-- This migration:
--   1. Demotes every active user with designation = 'TRIAGE_NURSE' to
--      'STAFF_NURSE' — a safe, conservative seniority default. Admins
--      can re-classify any individuals to SENIOR_NURSE later if their
--      seniority warrants it.
--   2. Idempotent: re-running has no effect because no rows match the
--      WHERE clause after the first run.
--   3. Logs a NOTICE so post-deploy verification can confirm the
--      change applied.
--
-- Triage station assignments going forward live in
-- ShiftAssignment.shiftFunction = TRIAGE_NURSE. Querying "who's at
-- triage right now?" becomes a join on the active shift roster.
-- ════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    affected INT;
BEGIN
    UPDATE users
       SET designation = 'STAFF_NURSE'
     WHERE designation = 'TRIAGE_NURSE';

    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'V45: demoted % user(s) from designation=TRIAGE_NURSE to STAFF_NURSE',
                 affected;

    -- Safety check — no rows should remain with the deprecated value.
    SELECT COUNT(*) INTO affected
      FROM users
     WHERE designation = 'TRIAGE_NURSE';
    IF affected > 0 THEN
        RAISE EXCEPTION
            'V45 safety check failed: % rows still have designation=TRIAGE_NURSE',
            affected;
    END IF;
END $$;
