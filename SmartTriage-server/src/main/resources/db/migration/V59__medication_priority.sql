-- ============================================================
-- V59 — Structured medication priority
-- ============================================================
--
-- Workflow 3 (doctor → nurse medication administration). Adds a
-- structured urgency tier so the system can:
--
--   • sort the nurse medication queue STAT-first,
--   • escalate via the STAT/URGENT SLA monitor (10 / 30 min after
--     prescribedAt without an administration),
--   • prioritise the real-time toast that fires when a new
--     prescription lands while the nurse is on another chart.
--
-- Values must match com.smartTriage.smartTriage_server.common.enums
-- .MedicationPriority. Existing rows are backfilled to ROUTINE
-- because no urgency signal was captured before this column landed;
-- legacy "STAT" tokens in the free-text frequency column are NOT
-- promoted automatically — that text is a UI hint, not a contract.
-- ============================================================

ALTER TABLE medication_administrations
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'ROUTINE';

CREATE INDEX idx_med_admin_priority ON medication_administrations(priority);

ALTER TABLE medication_administrations
    ADD CONSTRAINT ck_med_admin_priority
    CHECK (priority IN ('STAT', 'URGENT', 'ROUTINE'));
