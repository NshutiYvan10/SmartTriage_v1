-- V102 — Additional (non-national-form) triage signs.
--
-- The triage UI captures a RICHER set of clinical signs than the Rwanda National
-- form's fixed checkboxes (e.g. capillary refill > 3s, cold peripheries, tracheal
-- deviation, suspected pneumothorax, eclampsia features, anaphylaxis). Rather than
-- drop these (patient-safety hazard) or record them as inert free text, each extra
-- sign the assessor checks is carried at its acuity tier and:
--   * DRIVES the triage colour — RwandaTriageDecisionEngine treats a non-empty
--     additional-emergency list as RED, additional-very-urgent as ORANGE, and
--     additional-urgent as YELLOW (unioned with the national-form checks); and
--   * is PERSISTED here for the medico-legal record.
--
-- Stored as " | "-joined human-readable sign labels (nullable; NULL = none selected).
-- Purely additive: existing rows are unaffected and default to NULL.
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS additional_emergency_signs TEXT;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS additional_very_urgent_signs TEXT;
ALTER TABLE triage_records ADD COLUMN IF NOT EXISTS additional_urgent_signs TEXT;
