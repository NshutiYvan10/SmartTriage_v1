-- V55 — Shift-template auto-sync + clinical shiftFunction↔zone rules.
--
-- Three coordinated changes:
--
--   1. shift_assignments.template_id (NULLABLE FK to shift_templates)
--      gives each materialized assignment a back-link to the template it
--      was applied from. When a Charge Nurse edits the template, the
--      service uses this column to find every future calendar slot that
--      came from that template and propagate the change. NULL means the
--      assignment was created manually (no template), so it's
--      intentionally excluded from auto-sync.
--
--   2. CHECK constraints on shift_assignments + shift_template_assignments
--      codify the clinical rules about which shiftFunction values may
--      pair with which zones. Defence-in-depth — even a buggy service
--      call can't write a TRIAGE_NURSE into the ACUTE zone.
--
--      Rules:
--        - TRIAGE_NURSE      → zone MUST be TRIAGE
--        - ZONE_NURSE        → zone MUST NOT be TRIAGE
--        - PRIMARY_DOCTOR    → zone MUST NOT be TRIAGE (doctors don't triage)
--        - SUPERVISING_DOCTOR→ zone MUST NOT be TRIAGE
--        - RESIDENT          → zone MUST NOT be TRIAGE
--        - CHARGE_NURSE      → any zone (operational role, works floor-wide)

-- ── 1. template_id FK on shift_assignments ─────────────────────────
ALTER TABLE shift_assignments
    ADD COLUMN template_id UUID NULL
    REFERENCES shift_templates(id) ON DELETE SET NULL;

-- Partial index — most rows are NULL (manual assignments); we only
-- query when looking up "future rows that came from template X".
CREATE INDEX idx_shift_assignment_template
    ON shift_assignments (template_id, shift_date)
    WHERE template_id IS NOT NULL;

-- ── 2. Backfill existing data BEFORE adding CHECK constraints, so any
-- historical rows that already violate the new rules are corrected and
-- the constraint attaches cleanly. On a fresh install these are no-ops.
--
-- For violators, the safest correction:
--   TRIAGE_NURSE in non-TRIAGE → demote shift_function to ZONE_NURSE
--   any other function with zone=TRIAGE → move to GENERAL (typical
--     fallback treatment zone in Rwandan EDs)

UPDATE shift_assignments
    SET shift_function = 'ZONE_NURSE'
    WHERE shift_function = 'TRIAGE_NURSE' AND zone <> 'TRIAGE';

UPDATE shift_assignments
    SET zone = 'GENERAL'
    WHERE shift_function IN ('ZONE_NURSE', 'PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT')
      AND zone = 'TRIAGE';

UPDATE shift_template_assignments
    SET shift_function = 'ZONE_NURSE'
    WHERE shift_function = 'TRIAGE_NURSE' AND zone <> 'TRIAGE';

UPDATE shift_template_assignments
    SET zone = 'GENERAL'
    WHERE shift_function IN ('ZONE_NURSE', 'PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT')
      AND zone = 'TRIAGE';

-- ── 3. shiftFunction ↔ zone CHECK constraints ──────────────────────
-- Same predicate on both tables — template_assignment is the master,
-- shift_assignment is the materialized copy.

ALTER TABLE shift_assignments
    ADD CONSTRAINT ck_shift_assignment_role_zone CHECK (
        (shift_function = 'TRIAGE_NURSE' AND zone = 'TRIAGE')
        OR (shift_function = 'CHARGE_NURSE')
        OR (shift_function IN ('ZONE_NURSE', 'PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT')
            AND zone <> 'TRIAGE')
    );

ALTER TABLE shift_template_assignments
    ADD CONSTRAINT ck_shift_template_assignment_role_zone CHECK (
        (shift_function = 'TRIAGE_NURSE' AND zone = 'TRIAGE')
        OR (shift_function = 'CHARGE_NURSE')
        OR (shift_function IN ('ZONE_NURSE', 'PRIMARY_DOCTOR', 'SUPERVISING_DOCTOR', 'RESIDENT')
            AND zone <> 'TRIAGE')
    );
