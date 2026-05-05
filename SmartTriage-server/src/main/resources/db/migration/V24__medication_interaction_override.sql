-- V24: Record drug–drug interaction overrides on medication administrations.
--
-- Background: Phase 8 added a hard-stop dialog at prescribe time that
-- cross-references the drug being ordered against the patient's other
-- active medications using a hardcoded high-value interaction table
-- (warfarin + NSAID, MAOI + SSRI, opioid + benzo, etc.). When the
-- prescriber acknowledges the dialog and proceeds, we need a permanent
-- audit row — same reasoning as V23 for allergy overrides.
--
-- These columns mirror V23 but capture interaction conflicts. They're
-- kept distinct from the allergy columns because:
--   - The two checks are conceptually different (patient-property vs.
--     visit-state) and a single order can hit one, the other, or both.
--   - QA reports may want to filter on each independently.
--   - Mechanism-of-action text is interaction-specific (e.g.
--     "CYP inhibition → INR↑") and doesn't share format with allergy
--     reasons.
--
-- All three columns are nullable / default-false so existing rows are
-- valid without backfill.

ALTER TABLE medication_administrations
    ADD COLUMN IF NOT EXISTS prescribed_despite_interaction
        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS interaction_override_matches
        TEXT,
    ADD COLUMN IF NOT EXISTS interaction_override_acknowledged_at
        TIMESTAMPTZ;

-- Partial index: same shape as the allergy-override index. Most rows
-- are FALSE; the QA query only filters on TRUE.
CREATE INDEX IF NOT EXISTS idx_med_admin_interaction_override
    ON medication_administrations (prescribed_at DESC)
    WHERE prescribed_despite_interaction = TRUE
      AND is_active = TRUE;

COMMENT ON COLUMN medication_administrations.prescribed_despite_interaction IS
    'TRUE when the prescriber acknowledged a drug–drug interaction conflict at order time and chose to prescribe anyway.';
COMMENT ON COLUMN medication_administrations.interaction_override_matches IS
    'Snapshot of the conflicting interactions at decision time. Format: "<other drug> + <prescribed class>/<other class>: <mechanism> [<severity>]; ..." e.g. "Warfarin 5mg + aspirin/warfarin: additive bleeding risk [major]".';
COMMENT ON COLUMN medication_administrations.interaction_override_acknowledged_at IS
    'Server timestamp when the interaction override was confirmed by the prescriber.';
