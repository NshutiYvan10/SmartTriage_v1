-- ============================================================
-- V60 — Multi-zone coverage for a single shift assignment
-- ============================================================
--
-- Workflow 4 (small-hospital multi-zone doctor coverage). The
-- existing shift_assignments row carries a single PRIMARY zone in
-- the `zone` column — the clinician's posted station. This table
-- adds zero-or-more ADDITIONAL zones the same clinician also
-- covers on that shift.
--
-- Rationale: a 1-2-doctor ED at a district hospital can't post a
-- separate physician per zone. Currently the system filters that
-- doctor's patient list to a single zone (their primary), so
-- patients in RESUS are invisible to the doctor posted in ACUTE.
-- This is a documented silent-failure mode — the doctor doesn't
-- see acutely-deteriorating patients in adjacent zones.
--
-- Strictly additive: every existing code path that reads
-- shift_assignments.zone keeps working. The new column is a side
-- channel surfaced to:
--   • VisitService.getActiveVisitsForCaller — union of primary +
--     additional zones in the active-patient query,
--   • the frontend WebSocket subscription — one /topic/alerts/
--     {hospitalId}/{zone} subscription per covered zone,
--   • the dashboard header — display all covered zones as chips
--     so the clinician knows what they're seeing.
--
-- Validation lives in ShiftAssignmentService:
--   • the additional set must not include the primary zone
--     (no point — primary is already covered),
--   • each additional zone is unique within the set.
-- ============================================================

CREATE TABLE shift_assignment_additional_zones (
    shift_assignment_id UUID        NOT NULL REFERENCES shift_assignments(id) ON DELETE CASCADE,
    zone                VARCHAR(20) NOT NULL,
    PRIMARY KEY (shift_assignment_id, zone)
);

CREATE INDEX idx_shift_addl_zone ON shift_assignment_additional_zones(zone);

ALTER TABLE shift_assignment_additional_zones
    ADD CONSTRAINT ck_shift_addl_zone
    CHECK (zone IN (
        'RESUS', 'ACUTE', 'GENERAL', 'AMBULATORY', 'TRIAGE',
        'OBSERVATION', 'ISOLATION', 'PEDIATRIC', 'NEONATAL'
    ));
