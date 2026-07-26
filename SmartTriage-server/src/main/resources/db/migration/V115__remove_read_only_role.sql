-- V115 — Remove the READ_ONLY role.
--
-- Background: READ_ONLY was a view-only "safety officer / auditor"
-- account category. In practice the governance surfaces it covered
-- (reports, audit trail, quality metrics, override register) are all
-- available to HOSPITAL_ADMIN, and no deployment created dedicated
-- read-only staff accounts. Keeping a whole role for a persona nobody
-- holds widened every authorization matrix (each endpoint had to
-- decide whether READ_ONLY belonged on its list) for no operational
-- gain, so the role is retired.
--
-- This migration:
--   1. Converts any existing READ_ONLY users to HOSPITAL_ADMIN — the
--      nearest surviving role for governance/audit access — but
--      DEACTIVATES them at the same time. Reactivation (and thereby
--      the strictly larger HOSPITAL_ADMIN authority) must be an
--      explicit, audited decision by an administrator, not a silent
--      side effect of a schema migration.
--   2. Follows the V39 precedent (TRIAGE_NURSE merged into NURSE):
--      the Java Role enum drops READ_ONLY in the same release; this
--      migration MUST run before that code deploys, otherwise existing
--      READ_ONLY rows would fail enum deserialization.

UPDATE users
SET role      = 'HOSPITAL_ADMIN',
    is_active = FALSE
WHERE role = 'READ_ONLY';
