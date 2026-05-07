-- ════════════════════════════════════════════════════════════════════════
-- V29 — Triage Nurse role/designation restructure
--
-- Triage Nurse was incorrectly modelled as a top-level Role. A triage
-- nurse is still a nurse — the "triage" part is a *function* she performs
-- on the unit, not a different profession. The right model is:
--   role        = NURSE
--   designation = TRIAGE_NURSE   (alongside CHARGE_NURSE, SENIOR_NURSE, ...)
--
-- This migration re-points any existing users who were created under the
-- old model so they don't get locked out the moment the new code deploys
-- (the JPA Role enum no longer contains TRIAGE_NURSE; an unmapped value
-- in the role column would fail entity hydration).
--
-- Idempotent: re-running has no effect because no rows match the WHERE
-- clause after the first execution.
-- ════════════════════════════════════════════════════════════════════════

UPDATE users
SET role        = 'NURSE',
    designation = 'TRIAGE_NURSE'
WHERE role = 'TRIAGE_NURSE';

-- Note: we deliberately do NOT touch any other related rows (shift
-- assignments, audit logs, training records). The user's identity hasn't
-- changed; only their RBAC label has been corrected. Their existing
-- shift assignments / audit history still refer to the same user_id.

-- Sanity log so post-deploy verification can confirm the migration ran:
DO $$
DECLARE
    remaining INT;
BEGIN
    SELECT COUNT(*) INTO remaining FROM users WHERE role = 'TRIAGE_NURSE';
    IF remaining > 0 THEN
        RAISE EXCEPTION
            'V29 migration safety check failed: % rows still have role=TRIAGE_NURSE',
            remaining;
    END IF;
    RAISE NOTICE 'V29 migration complete: 0 users remain with role=TRIAGE_NURSE';
END $$;
