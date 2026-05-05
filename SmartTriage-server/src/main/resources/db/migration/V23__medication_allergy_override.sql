-- V23: Record allergy overrides on medication administrations.
--
-- Background: the prescribe form on the frontend now cross-checks the
-- patient's free-text known-allergy list against the drug name (with a
-- cross-reactivity table covering penicillins, sulfa, NSAIDs, etc.) and
-- requires the clinician to explicitly confirm a hard-stop dialog
-- before the order goes through.
--
-- Without these columns the override is invisible after the fact —
-- there's no way for a clinical safety officer to query "show me every
-- medication prescribed against a known allergy in the last 30 days."
-- This migration captures three things:
--
--   1. WHETHER an override happened at all (boolean, indexed for QA).
--   2. WHAT the conflicting allergens were at decision time (free-text
--      snapshot, so a later change to the patient profile doesn't
--      rewrite history).
--   3. WHEN the override was acknowledged (separate from prescribed_at
--      because in theory the dialog could be dismissed and re-shown,
--      though the current UI doesn't do that).
--
-- All three are nullable / default-false so existing rows are valid
-- without backfill.

ALTER TABLE medication_administrations
    ADD COLUMN IF NOT EXISTS prescribed_despite_allergy
        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS allergy_override_matches
        TEXT,
    ADD COLUMN IF NOT EXISTS allergy_override_acknowledged_at
        TIMESTAMPTZ;

-- Partial index: most rows are FALSE and uninteresting. The QA query
-- ("recent overrides") only ever filters on TRUE, so a partial index
-- keeps the index small and the scan tight.
CREATE INDEX IF NOT EXISTS idx_med_admin_allergy_override
    ON medication_administrations (prescribed_at DESC)
    WHERE prescribed_despite_allergy = TRUE
      AND is_active = TRUE;

COMMENT ON COLUMN medication_administrations.prescribed_despite_allergy IS
    'TRUE when the prescriber acknowledged a known-allergy conflict at order time and chose to prescribe anyway.';
COMMENT ON COLUMN medication_administrations.allergy_override_matches IS
    'Snapshot of the conflicting allergens at decision time. Format: "<token> [(<family>)]; ..." e.g. "penicillin (penicillins/beta-lactam); sulfa (sulfa drugs)". Free-text on purpose — this is an audit record, not structured data.';
COMMENT ON COLUMN medication_administrations.allergy_override_acknowledged_at IS
    'Server timestamp when the override was confirmed by the prescriber.';
