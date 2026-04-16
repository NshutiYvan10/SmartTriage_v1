-- ============================================================================
-- V16 — Shift templates + shift-lead badge
--
-- Introduces:
--   1. `is_shift_lead` flag on shift_assignments (the rotating "hat")
--   2. `shift_templates` — per-hospital default layouts for DAY / NIGHT shifts
--   3. `shift_template_assignments` — user-to-zone rows inside a template
--
-- Design notes:
-- * Exactly one shift-lead per (hospital, shift_date, shift_period): enforced
--   with a partial unique index (only rows where is_shift_lead = TRUE AND
--   is_active = TRUE are unique on the triple).
-- * Templates are idempotent: a scheduler materializes them into
--   `shift_assignments` at shift-boundary times (06:45 / 18:45). Re-running
--   the materializer for an already-materialized shift is a no-op.
-- * Exactly one active template per (hospital, shift_period): enforced with a
--   second partial unique index.
-- ============================================================================

-- ─── 1. Shift-lead badge on shift_assignments ────────────────────────────────
ALTER TABLE shift_assignments
    ADD COLUMN is_shift_lead BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial unique index: at most one active shift-lead per shift period.
CREATE UNIQUE INDEX uk_shift_lead_per_shift
    ON shift_assignments (hospital_id, shift_date, shift_period)
    WHERE is_shift_lead = TRUE AND is_active = TRUE;

CREATE INDEX idx_shift_assignments_is_shift_lead
    ON shift_assignments (is_shift_lead)
    WHERE is_shift_lead = TRUE;


-- ─── 2. shift_templates ─────────────────────────────────────────────────────
-- A named, reusable layout for a given shift period at a given hospital.
-- Normal hospitals have two active templates: one for DAY, one for NIGHT.
CREATE TABLE shift_templates (
    id                 UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id        UUID                     NOT NULL REFERENCES hospitals(id),
    name               VARCHAR(120)             NOT NULL,
    description        TEXT,
    shift_period       VARCHAR(15)              NOT NULL,
    -- Audit / base-entity columns
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(255),
    last_modified_by   VARCHAR(255),
    is_active          BOOLEAN                  NOT NULL DEFAULT TRUE,
    version            BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_shift_template_period CHECK (shift_period IN ('DAY', 'NIGHT'))
);

CREATE INDEX idx_shift_template_hospital
    ON shift_templates (hospital_id);

CREATE INDEX idx_shift_template_period
    ON shift_templates (hospital_id, shift_period, is_active);

-- At most one active template per (hospital, period) — the "current" template
-- that the scheduler materializes at shift boundary.
CREATE UNIQUE INDEX uk_shift_template_active_per_period
    ON shift_templates (hospital_id, shift_period)
    WHERE is_active = TRUE;


-- ─── 3. shift_template_assignments ──────────────────────────────────────────
-- Individual user-to-zone rows that make up a template. Each row says:
--   "Nurse X works zone RESUS as function ZONE_NURSE whenever this template
--    is applied."
CREATE TABLE shift_template_assignments (
    id                 UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id        UUID                     NOT NULL REFERENCES shift_templates(id) ON DELETE CASCADE,
    user_id            UUID                     NOT NULL REFERENCES users(id),
    zone               VARCHAR(20)              NOT NULL,
    shift_function     VARCHAR(30)              NOT NULL,
    is_shift_lead      BOOLEAN                  NOT NULL DEFAULT FALSE,
    -- Audit / base-entity columns
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(255),
    last_modified_by   VARCHAR(255),
    is_active          BOOLEAN                  NOT NULL DEFAULT TRUE,
    version            BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT uk_shift_template_user UNIQUE (template_id, user_id)
);

CREATE INDEX idx_shift_template_assignment_template
    ON shift_template_assignments (template_id)
    WHERE is_active = TRUE;

CREATE INDEX idx_shift_template_assignment_user
    ON shift_template_assignments (user_id);

-- At most one shift-lead row per template.
CREATE UNIQUE INDEX uk_shift_template_lead
    ON shift_template_assignments (template_id)
    WHERE is_shift_lead = TRUE AND is_active = TRUE;
