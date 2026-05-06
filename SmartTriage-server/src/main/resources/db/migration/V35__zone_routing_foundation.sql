-- V35 — Zone routing foundation.
--
-- Stage 1 of the zone-assignment workflow design. Three additions:
--
-- 1. visits.current_ed_zone — the canonical "where is this patient now"
--    field. Previously zone was computed on-the-fly from triage
--    category via EdZone.fromTriageCategory(). That works for alert
--    routing but not for the doctor-side question "show me only the
--    patients in my zone" — that needs a stored, queryable column.
--
--    On creation: NULL (patient hasn't been triaged yet).
--    After triage: set to the zone implied by the chosen category +
--    pediatric flag + hospital config.
--    On re-triage: updated as part of TriageService.systemTriggeredRetriage
--    (Phase 2 will add the ZoneTransfer state machine that gates the
--     change behind nurse acceptance for true inter-zone moves).
--
--    Backfill below sets it for every existing visit with a current
--    triage category, mirroring fromTriageCategory's logic so historical
--    rows end up self-consistent.
--
-- 2. visits.primary_clinician_id — soft binding to the doctor of record
--    for accountability and handover. NULL until the first clinical
--    action is taken on the patient by a doctor (Phase 2 wires the
--    auto-assignment).
--
-- 3. hospitals.has_pediatric_resus — single boolean per hospital. When
--    true, a pediatric RED patient is routed to PEDIATRIC zone (the
--    hospital has full peds resus capability there). When false (the
--    safe default for most facilities), pediatric RED routes to the
--    main RESUS zone — that's where the gear is.

ALTER TABLE visits
    ADD COLUMN current_ed_zone VARCHAR(20) NULL,
    ADD COLUMN primary_clinician_id UUID NULL REFERENCES users(id);

CREATE INDEX idx_visits_current_ed_zone
    ON visits (current_ed_zone)
    WHERE current_ed_zone IS NOT NULL;

CREATE INDEX idx_visits_primary_clinician
    ON visits (primary_clinician_id)
    WHERE primary_clinician_id IS NOT NULL;

ALTER TABLE hospitals
    ADD COLUMN has_pediatric_resus BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill current_ed_zone for visits that already have a category.
-- Mirrors EdZone.fromTriageCategory:
--   RED   → RESUS
--   ORANGE → ACUTE
--   YELLOW / GREEN → GENERAL
--   BLUE → GENERAL (handled separately downstream)
-- Pediatric override: peds patients (non-RED) → PEDIATRIC zone.
-- Pediatric RED: defer to has_pediatric_resus, defaults to FALSE so
-- they land in RESUS — the conservative direction.
UPDATE visits v
SET current_ed_zone = CASE
    WHEN v.current_triage_category = 'RED' AND v.is_pediatric = true
         AND COALESCE((SELECT h.has_pediatric_resus FROM hospitals h
                       WHERE h.id = v.hospital_id), false) = true
        THEN 'PEDIATRIC'
    WHEN v.current_triage_category = 'RED' THEN 'RESUS'
    WHEN v.is_pediatric = true THEN 'PEDIATRIC'
    WHEN v.current_triage_category = 'ORANGE' THEN 'ACUTE'
    WHEN v.current_triage_category IN ('YELLOW', 'GREEN', 'BLUE') THEN 'GENERAL'
    ELSE NULL
END
WHERE v.current_triage_category IS NOT NULL
  AND v.current_ed_zone IS NULL;
