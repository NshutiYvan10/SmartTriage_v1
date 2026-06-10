-- ═══════════════════════════════════════════════════════════════
-- V65 — acknowledgment note on clinical_alerts (B5).
--
-- BACKGROUND
-- ----------
-- The alert acknowledge/dismiss dialog captures a free-text comment from
-- the clinician ("IV fluids given, monitoring continues"), but it was
-- silently dropped: the frontend API call sent no body, the acknowledge
-- endpoint/service took no comment parameter, and there was no column to
-- store it. The note is clinically useful audit context (what was done in
-- response to the alert), so losing it is a data-fidelity gap.
--
-- WHAT THIS DOES
-- --------------
-- Adds a nullable acknowledgment_note column. The acknowledge endpoint now
-- accepts an optional note and persists it. For a dialog "dismiss" the
-- frontend prefixes the note with [Dismissed] so the action taken is
-- auditable (both dialog modes acknowledge on the backend today; a fully
-- distinct dismiss workflow is a separate product decision).
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE clinical_alerts
    ADD COLUMN IF NOT EXISTS acknowledgment_note VARCHAR(1000);

COMMENT ON COLUMN clinical_alerts.acknowledgment_note IS
    'Free-text note the clinician entered when acknowledging/dismissing the alert (B5). Nullable.';
