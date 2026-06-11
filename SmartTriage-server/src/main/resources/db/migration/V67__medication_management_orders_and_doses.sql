-- ═══════════════════════════════════════════════════════════════
-- V67 — Medication Management module: typed orders + dose-level events.
--
-- BACKGROUND
-- ----------
-- medication_administrations was a single-shot MAR row: one prescription
-- = one administration (prescribe → administer → countersign). The ED
-- needs the full prescription taxonomy:
--   ONE_TIME    "Morphine 4 mg IV once"
--   SCHEDULED   "Ceftriaxone 1 g IV every 24 hours"
--   PRN         "Paracetamol 1 g PO q6h PRN pain"
--   CONTINUOUS  "Normal saline at 100 mL/hr"
-- plus special administrations (blood transfusions, blood products,
-- fluid boluses) that follow the same order→administer workflow.
--
-- DESIGN
-- ------
-- 1. medication_administrations becomes the ORDER. New columns describe
--    the administration pattern. prescription_type is NULLABLE: legacy
--    rows (and old API clients that don't send a type) keep the exact
--    pre-V67 single-shot behaviour — NULL is treated as ONE_TIME with
--    the legacy administer flow.
-- 2. NEW TABLE medication_doses — one row per administration EVENT
--    (scheduled dose, PRN dose, infusion start/rate-change/stop). A
--    recurring order accumulates many dose rows; together they are the
--    per-patient medication audit trail.
-- 3. handover_reports gains a dedicated medication_audit section.
--
-- All columns are additive; no existing data is modified.
-- ═══════════════════════════════════════════════════════════════

-- ── 1. Order-level columns on medication_administrations ──

ALTER TABLE medication_administrations
    -- Administration pattern. NULL = legacy single-shot row (pre-V67
    -- behaviour preserved exactly).
    ADD COLUMN IF NOT EXISTS prescription_type        VARCHAR(20),
    -- What is administered. DRUG | BLOOD_PRODUCT | IV_FLUID | OTHER.
    ADD COLUMN IF NOT EXISTS product_type             VARCHAR(20) NOT NULL DEFAULT 'DRUG',
    -- Free-text detail for non-drug products ("PRBC 2 units", "FFP 4 units").
    ADD COLUMN IF NOT EXISTS product_detail           VARCHAR(255),

    -- Structured dose (legacy free-text `dose` column stays for display
    -- and old clients; the structured pair drives verification).
    ADD COLUMN IF NOT EXISTS dose_value               NUMERIC(10,3),
    ADD COLUMN IF NOT EXISTS dose_unit                VARCHAR(20),

    -- Schedule (SCHEDULED type): first dose at start_at, then every
    -- interval_hours until end_at or max_doses GIVEN doses.
    ADD COLUMN IF NOT EXISTS start_at                 TIMESTAMP,
    ADD COLUMN IF NOT EXISTS interval_hours           NUMERIC(6,2),
    ADD COLUMN IF NOT EXISTS end_at                   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS max_doses                INTEGER,

    -- PRN controls.
    ADD COLUMN IF NOT EXISTS prn_indication           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS prn_min_interval_hours   NUMERIC(6,2),
    ADD COLUMN IF NOT EXISTS prn_max_doses_per_day    INTEGER,

    -- Structured vitals gate for PRN ("only if SBP >= 180").
    ADD COLUMN IF NOT EXISTS gate_parameter           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS gate_comparator          VARCHAR(3),
    ADD COLUMN IF NOT EXISTS gate_threshold           NUMERIC(8,2),

    -- Continuous infusion prescription rate.
    ADD COLUMN IF NOT EXISTS rate_value               NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS rate_unit                VARCHAR(20),

    -- High-alert approval gate (V67): formulary high-alert drugs start
    -- PENDING_APPROVAL until a charge nurse approves, unless the
    -- prescriber invoked the emergency override with justification.
    ADD COLUMN IF NOT EXISTS approval_required        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS approved_by_id           UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS approved_by_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS approved_at              TIMESTAMP,
    ADD COLUMN IF NOT EXISTS approval_note            VARCHAR(500),
    ADD COLUMN IF NOT EXISTS emergency_override       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS emergency_justification  TEXT,

    -- Two-person verification requirement at administration time
    -- (blood products always; formulary requires_double_check drugs).
    ADD COLUMN IF NOT EXISTS requires_witness         BOOLEAN NOT NULL DEFAULT FALSE,

    -- Discontinue workflow (doctor actively stops an order, with reason).
    ADD COLUMN IF NOT EXISTS discontinued_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS discontinued_by_id       UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS discontinued_by_name     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS discontinue_reason       VARCHAR(500),

    -- Planned end reached (duration elapsed / max doses given).
    ADD COLUMN IF NOT EXISTS completed_at             TIMESTAMP,

    -- Modification chain: an order is never edited in place — it is
    -- discontinued and replaced. supersedes_id points at the order this
    -- one replaced; superseded_by_id is the back-pointer. The chain IS
    -- the prescription modification history.
    ADD COLUMN IF NOT EXISTS supersedes_id            UUID REFERENCES medication_administrations(id),
    ADD COLUMN IF NOT EXISTS superseded_by_id         UUID REFERENCES medication_administrations(id);

CREATE INDEX IF NOT EXISTS idx_med_admin_type        ON medication_administrations(prescription_type);
CREATE INDEX IF NOT EXISTS idx_med_admin_supersedes  ON medication_administrations(supersedes_id);

COMMENT ON COLUMN medication_administrations.prescription_type IS
    'ONE_TIME | SCHEDULED | PRN | CONTINUOUS. NULL = legacy pre-V67 single-shot row (treated as ONE_TIME, legacy administer flow).';
COMMENT ON COLUMN medication_administrations.supersedes_id IS
    'Order this one replaced via the modify workflow. The supersedes chain is the prescription modification history.';

-- ── 2. Dose-level event table ──

CREATE TABLE IF NOT EXISTS medication_doses (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    medication_id         UUID          NOT NULL REFERENCES medication_administrations(id),
    visit_id              UUID          NOT NULL REFERENCES visits(id),

    -- What this row records and where it is in its lifecycle.
    kind                  VARCHAR(24)   NOT NULL,
    status                VARCHAR(16)   NOT NULL DEFAULT 'DUE',
    sequence_number       INTEGER,

    -- Scheduling.
    due_at                TIMESTAMP,

    -- Administration record.
    given_at              TIMESTAMP,
    given_by_id           UUID          REFERENCES users(id),
    given_by_name         VARCHAR(255),
    witness_name          VARCHAR(255),

    -- Verified administered dose (the nurse confirms what they are
    -- giving; mismatch vs the order is rejected unless overridden).
    dose_value            NUMERIC(10,3),
    dose_unit             VARCHAR(20),

    -- Infusion events.
    rate_value            NUMERIC(10,2),
    rate_unit             VARCHAR(20),

    -- PRN administration context.
    prn_reason            VARCHAR(255),

    -- Human-readable snapshot of the vitals-gate evaluation at
    -- administration time ("SBP 102 >= 100 — passed").
    gate_evaluation       VARCHAR(500),

    -- Override trail (vitals gate failed / dose mismatch / allergy
    -- recheck) — justification is mandatory when is_override is true.
    is_override           BOOLEAN       NOT NULL DEFAULT FALSE,
    override_justification TEXT,

    -- Delay / refuse / miss / cancel reason trail (append-only text).
    status_reason         TEXT,
    delay_count           INTEGER       NOT NULL DEFAULT 0,

    -- Scheduler bookkeeping — when the overdue re-notification and the
    -- missed escalation fired, so monitors don't spam every tick.
    overdue_notified_at   TIMESTAMP,
    missed_escalated_at   TIMESTAMP,

    -- BaseEntity columns
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP,
    created_by            VARCHAR(255),
    last_modified_by      VARCHAR(255),
    is_active             BOOLEAN       NOT NULL DEFAULT true,
    version               BIGINT
);

CREATE INDEX IF NOT EXISTS idx_med_dose_medication ON medication_doses(medication_id);
CREATE INDEX IF NOT EXISTS idx_med_dose_visit      ON medication_doses(visit_id);
CREATE INDEX IF NOT EXISTS idx_med_dose_status_due ON medication_doses(status, due_at);
CREATE INDEX IF NOT EXISTS idx_med_dose_active     ON medication_doses(is_active);

COMMENT ON TABLE medication_doses IS
    'One row per medication administration EVENT (V67): scheduled/PRN/one-time doses and infusion start/rate-change/stop. A recurring order accumulates many rows; together they form the per-patient medication audit trail.';

-- ── 3. Handover report: dedicated medication audit section ──

ALTER TABLE handover_reports
    ADD COLUMN IF NOT EXISTS medication_audit TEXT;

COMMENT ON COLUMN handover_reports.medication_audit IS
    'Full medication audit trail at generation time (V67): active orders with schedule/remaining doses, every dose given (by whom, when), missed/held/refused/discontinued with reasons.';
