-- ════════════════════════════════════════════════════════════════════════
-- V28 — Direct Resus Admission
--
-- Adds the schema needed to admit a patient straight to the resuscitation
-- bay before any triage form is filled in. This is a first-class clinical
-- pathway in real EDs: a patient arrives in obvious extremis (cardiac
-- arrest, severe trauma, obstructed airway), the nurse declares RED by
-- clinical eye, and clinical intervention starts immediately. Paperwork
-- (identity, vitals, retrospective triage record) follows the patient.
--
-- This migration adds:
--   1. Patient identity placeholder fields (for unidentified arrivals).
--   2. Visit fields tracking resus-overflow and ambulance pre-arrival.
--   3. A per-hospital, per-day counter table that hands out daily NATO
--      phonetic placeholder names ("Alpha", "Bravo" ... "Zulu", then
--      "Alpha-2", etc.) atomically under concurrent admissions.
-- ════════════════════════════════════════════════════════════════════════

-- ────────────────────────────────────────────────────────────────────
-- 1. Patient — identity placeholder & resolution audit
--
-- An unidentified patient gets first_name='Unknown' and a NATO phonetic
-- last_name. is_unidentified flips to false when the nurse/doctor
-- resolves the identity; placeholder_label is preserved for audit.
-- ────────────────────────────────────────────────────────────────────
ALTER TABLE patients
    ADD COLUMN is_unidentified           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN placeholder_label         VARCHAR(50) NULL,
    ADD COLUMN placeholder_assigned_at   TIMESTAMP   NULL,
    ADD COLUMN identified_at             TIMESTAMP   NULL,
    ADD COLUMN identified_by_user_id     UUID        NULL
        REFERENCES users(id);

-- Partial index — only non-resolved unidentified patients are queried
-- by the identity-overdue scheduler and the "needs identification" badge.
CREATE INDEX idx_patient_unidentified
    ON patients(is_unidentified, placeholder_assigned_at)
    WHERE is_unidentified = TRUE;

-- ────────────────────────────────────────────────────────────────────
-- 2. Visit — resus-overflow & ambulance pre-arrival
--
-- pending_resus_overflow: TRUE when a Direct Resus admission could not
--   immediately get a RESUS bed (all occupied). The visit is recorded and
--   a transfer prompt is shown so the charge nurse can move out a
--   stabilised patient to free space. Cleared as soon as a bed is placed.
--
-- ambulance_pre_arrival: TRUE when the visit was created from an
--   ambulance call-ahead (radio handover, ETA known). arrival_confirmed_at
--   stays NULL until the nurse marks the patient as physically arrived.
-- ────────────────────────────────────────────────────────────────────
ALTER TABLE visits
    ADD COLUMN pending_resus_overflow BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN ambulance_pre_arrival  BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN arrival_confirmed_at   TIMESTAMP NULL;

CREATE INDEX idx_visit_resus_overflow
    ON visits(pending_resus_overflow, hospital_id)
    WHERE pending_resus_overflow = TRUE;

CREATE INDEX idx_visit_ambulance_prearrival
    ON visits(ambulance_pre_arrival, hospital_id)
    WHERE ambulance_pre_arrival = TRUE AND arrival_confirmed_at IS NULL;

-- ────────────────────────────────────────────────────────────────────
-- 3. Daily NATO phonetic counter
--
-- Per-hospital, per-day. The counter is incremented atomically via
-- INSERT ... ON CONFLICT DO UPDATE ... RETURNING, so two simultaneous
-- Direct Resus admissions cannot race into the same placeholder name.
--
-- next_index semantics:
--   0  → "Alpha"
--   1  → "Bravo"
--   ...
--   25 → "Zulu"
--   26 → "Alpha-2"
--   ...
--
-- Adult and pediatric admissions share the same counter — the (child)
-- suffix at display time comes from the visit's is_pediatric flag, not
-- from the placeholder. So an adult Alpha and a child Alpha cannot
-- co-exist on the same day.
-- ────────────────────────────────────────────────────────────────────
CREATE TABLE unidentified_patient_counters (
    hospital_id   UUID    NOT NULL REFERENCES hospitals(id),
    sequence_date DATE    NOT NULL,
    next_index    INTEGER NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (hospital_id, sequence_date)
);

COMMENT ON TABLE unidentified_patient_counters IS
    'Per-hospital, per-day counter feeding the NATO phonetic placeholder name '
    'service. Incremented atomically on Direct Resus admission of an '
    'unidentified patient. Resets implicitly each day (new row).';
