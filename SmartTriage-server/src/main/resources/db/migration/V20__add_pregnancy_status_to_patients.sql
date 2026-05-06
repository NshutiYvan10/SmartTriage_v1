-- ═══════════════════════════════════════════════════════════════
-- V20 — Pregnancy status on patients (teratogen safety check).
--
-- BACKGROUND
-- ----------
-- Before this migration, pregnancy status was inferred by free-text scan of
-- chronic_conditions ('pregnant', misspellings, negations). That approach
-- failed silently — a missed match meant a pregnant patient could be
-- prescribed a teratogen with no warning.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- 1. Adds `pregnancy_status` (enum) and `pregnancy_status_recorded_at`
--    (nullable timestamp) to `patients`.
-- 2. Backfills every existing row to a non-null status so the safety check
--    can never encounter NULL on legacy data.
--
-- DEFAULT-VALUE LOGIC (clinically defensive)
-- ------------------------------------------
-- * gender = MALE        → NOT_APPLICABLE
--     Definitively cannot be pregnant. Safe to mark "skip teratogen check".
-- * gender IN (FEMALE, OTHER, UNKNOWN) → UNKNOWN
--     Pregnancy cannot be ruled out without asking. UNKNOWN signals to the
--     prescribing clinician "we have not confirmed this — ask before
--     prescribing teratogens". Defaulting OTHER/UNKNOWN to NOT_APPLICABLE
--     would silently skip exactly the records this column exists to catch
--     (intersex / non-binary patients with reproductive organs, ambiguous
--     records imported from paper forms).
--
-- recorded_at IS LEFT NULL FOR THE BACKFILL
-- -----------------------------------------
-- The semantic of `recorded_at` is "when a clinician affirmed this status".
-- For backfilled placeholder values, no clinician has affirmed anything;
-- writing NOW() would falsely imply confirmation just happened. NULL is
-- the truthful signal that this is a synthetic default awaiting clinical
-- confirmation. The frontend can use NULL to surface a "needs confirmation"
-- hint without lying about provenance.
--
-- COLUMN STAYS NULLABLE
-- ---------------------
-- We do not add NOT NULL here. Service-layer creation paths (PatientService
-- and PatientMapper) are being updated in the same change to set a default
-- on every new patient based on gender, mirroring the backfill rule. A DB
-- DEFAULT cannot encode the gender-conditional logic, and forcing NOT NULL
-- without a workable DEFAULT would break any code path that constructs
-- Patient without going through those updated factories (tests, fixtures).
-- The application contract — every patient row has a pregnancy_status —
-- is enforced by the service layer; the DB enforces it for legacy rows
-- via the backfill below.
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS pregnancy_status VARCHAR(20);

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS pregnancy_status_recorded_at TIMESTAMP WITH TIME ZONE;

-- Backfill: every existing row gets a clinically-defensive default.
UPDATE patients
SET pregnancy_status = CASE
        WHEN gender = 'MALE' THEN 'NOT_APPLICABLE'
        ELSE 'UNKNOWN'
    END
WHERE pregnancy_status IS NULL;

-- Optional check constraint — guards against typos / direct SQL writes.
-- Mirrors the Java enum so DB and app cannot drift.
ALTER TABLE patients
    DROP CONSTRAINT IF EXISTS chk_patients_pregnancy_status;

ALTER TABLE patients
    ADD CONSTRAINT chk_patients_pregnancy_status
    CHECK (pregnancy_status IS NULL OR pregnancy_status IN (
        'PREGNANT',
        'BREASTFEEDING',
        'POSSIBLY_PREGNANT',
        'NOT_PREGNANT',
        'NOT_APPLICABLE',
        'UNKNOWN'
    ));

-- Index for any future query that filters or aggregates by status
-- (e.g. "list all PREGNANT patients in the ED" for safety dashboards).
CREATE INDEX IF NOT EXISTS idx_patient_pregnancy_status
    ON patients (pregnancy_status);

COMMENT ON COLUMN patients.pregnancy_status
    IS 'Drives teratogen safety checks at prescribe time. UNKNOWN means "asked, do not know" — never silently skipped. See PregnancyStatus enum.';
COMMENT ON COLUMN patients.pregnancy_status_recorded_at
    IS 'When a clinician affirmed the current pregnancy_status. NULL means the value is a synthetic default awaiting clinical confirmation.';
