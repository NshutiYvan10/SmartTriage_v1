-- V103 — Durable, restart-proof, per-hospital MRN sequence.
--
-- BUG THIS FIXES: patients.medical_record_number was minted from a static in-memory
-- AtomicLong(100000) (PatientService.mrnCounter) shared across ALL hospitals. It reset
-- to 100000 on every application restart, so the first post-restart registration
-- re-issued <code>-100001 — which already exists — tripping the partial-unique index
-- uq_patient_mrn_per_hospital (V22) and rolling the whole registration transaction back.
-- The registrar saw a generic 409 "conflicts with existing data" and could not register
-- ANY new patient until the in-memory counter climbed back past the per-hospital max.
--
-- FIX: mirror the proven V96 visit-number fix — a counter persisted in the DB and
-- incremented atomically with INSERT … ON CONFLICT … DO UPDATE … RETURNING. Keyed on
-- hospital_code ALONE (NOT per-day): unlike a visit number, an MRN is a lifetime
-- identifier and its sequence must NEVER reset. Survives restarts; the row lock
-- serialises concurrent AND cross-instance registrations.
--
-- FORMAT PRESERVED: MRN stays "<hospital_code>-<n>" (e.g. KFH-001-100016). No existing
-- MRN is altered; new numbers simply continue above the current per-hospital max.

CREATE TABLE IF NOT EXISTS mrn_sequence_counters (
    hospital_code VARCHAR(40) NOT NULL PRIMARY KEY,
    next_index    BIGINT      NOT NULL DEFAULT 100000,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mrn_sequence_counters IS
    'Per-hospital lifetime monotonic counter feeding patients.medical_record_number (<code>-<n>). '
    'Replaces the in-memory AtomicLong that reset on restart and collided with existing MRNs. '
    'Incremented atomically (INSERT ON CONFLICT RETURNING). Unlike visit_sequence_counters it '
    'NEVER resets — no date in the key.';

-- BACKFILL / SEED: start each hospital's counter at its current high-water mark so the next
-- claim returns max+1 and continues seamlessly ABOVE every existing MRN. The MRN is
-- "<hospital_code>-<digits>" where hospital_code ITSELF contains a hyphen (e.g. KFH-001), so we
-- strip the leading "<code>-" by length and cast the remainder — NOT a naive split on the first
-- '-'. Only rows matching the exact "^<code>-<digits>$" shape are considered, so any NULL or
-- non-conforming MRN is ignored and cannot corrupt the max. The digit run is bounded to {1,15}:
-- generated MRNs are ~6 digits, 15 stays well under BIGINT's 19-digit ceiling, so an over-long
-- IMPORTED/legacy suffix is simply excluded (not matched) rather than overflowing the CAST and
-- aborting the whole migration. Seed to 100000 when a hospital has no conforming MRN yet
-- (matches the historical AtomicLong start; first claim returns 100001).
-- Idempotent: ON CONFLICT DO NOTHING leaves an existing counter untouched, so a re-run never
-- rewinds a live counter.
INSERT INTO mrn_sequence_counters (hospital_code, next_index, updated_at)
SELECT
    h.hospital_code,
    COALESCE(
        MAX(
            CAST(
                RIGHT(p.medical_record_number,
                      LENGTH(p.medical_record_number) - LENGTH(h.hospital_code) - 1)
                AS BIGINT)
        ),
        100000),
    CURRENT_TIMESTAMP
FROM hospitals h
LEFT JOIN patients p
    ON p.hospital_id = h.id
   AND p.is_active = TRUE
   AND p.medical_record_number ~ ('^' || h.hospital_code || '-[0-9]{1,15}$')
GROUP BY h.hospital_code
ON CONFLICT (hospital_code) DO NOTHING;
