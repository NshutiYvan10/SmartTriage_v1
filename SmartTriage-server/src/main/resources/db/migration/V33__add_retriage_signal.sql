-- V33 — Re-triage signal: link a triage_records row back to the
-- clinical-sign event that triggered it.
--
-- Round 3 of the clinical-signs project closes the loop: when a doctor
-- records a worsening EMERGENCY (or pediatric EMERGENCY) sign, the
-- system auto-creates a new TriageRecord with isSystemTriggered=true.
-- This column carries the audit link to the specific sign event so a
-- safety officer can trace "this RED came from cardiac-arrest going
-- PRESENT at 14:32 on visit V-CHUK-20260506-00042."
--
-- The column is NULL for every manual triage and for the system
-- triggered re-triages already on file (none yet — V33 is the
-- introduction). No backfill required.
--
-- The partial index keeps the FK lookup fast without storing index
-- entries for the long-tail of NULL-valued manual triages, which is
-- the bulk of the table.

ALTER TABLE triage_records
    ADD COLUMN triggering_sign_event_id UUID NULL
        REFERENCES clinical_sign_events(id);

CREATE INDEX idx_triage_triggering_sign
    ON triage_records (triggering_sign_event_id)
    WHERE triggering_sign_event_id IS NOT NULL;
