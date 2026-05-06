-- V36 — ZoneTransfer state machine.
--
-- Phase 2 of the zone-routing workflow. When a patient's category
-- changes such that the implied zone is different from where they
-- physically are, we now go through an explicit accept/decline step
-- instead of silently mutating visits.current_ed_zone. This matches
-- documented Rwandan ED practice (charge nurse coordinates moves;
-- receiving doctor explicitly takes the patient) and adds the missing
-- audit trail of who accepted what when.
--
-- Status semantics:
--   PENDING_ACCEPT — auto/manually proposed; both zones see the
--                    patient; original primary clinician retains
--                    responsibility until acceptance.
--   ACCEPTED       — receiving doctor took the patient. Visit's
--                    current_ed_zone + primary_clinician_id update on
--                    transition; original team sees a "transferred
--                    away" line on their handover log.
--   DECLINED       — receiving zone says no (e.g. resus full).
--                    Patient stays in original zone with a flag in
--                    declined_reason; alerts at the higher severity
--                    continue to fire. Charge nurse decides next.
--   RESUS_IN_PLACE — explicit "we're treating at higher acuity in the
--                    current physical location". Receiving doctor is
--                    co-responsible in-place. Common at district
--                    hospitals where there's only one resus bay.
--   CANCELLED      — initiator changed mind / system auto-bump was
--                    immediately undone within the cooldown.
--
-- Indexes are tuned for the two hot queries:
--   1. "Show me pending transfers into / out of zone X" — visit-id
--      + status, with status filtered to PENDING_ACCEPT.
--   2. "Charge nurse dashboard — all pending transfers for this
--      hospital" — joined through visits.hospital_id + a
--      created_at index.

CREATE TABLE zone_transfers (
    id                       UUID PRIMARY KEY,
    visit_id                 UUID NOT NULL REFERENCES visits(id),
    from_zone                VARCHAR(20) NULL,
    to_zone                  VARCHAR(20) NOT NULL,
    status                   VARCHAR(30) NOT NULL,
    reason                   TEXT,
    initiated_at             TIMESTAMPTZ NOT NULL,
    initiated_by_id          UUID NULL REFERENCES users(id),
    proposed_clinician_id    UUID NULL REFERENCES users(id),
    accepted_at              TIMESTAMPTZ NULL,
    accepted_by_id           UUID NULL REFERENCES users(id),
    declined_at              TIMESTAMPTZ NULL,
    declined_by_id           UUID NULL REFERENCES users(id),
    declined_reason          TEXT,
    handover_note            TEXT,
    triggering_alert_id      UUID NULL REFERENCES clinical_alerts(id),
    triggering_sign_event_id UUID NULL REFERENCES clinical_sign_events(id),
    -- BaseEntity columns — match the convention used by every other
    -- entity in the system: created_at / updated_at / version /
    -- is_active + created_by / last_modified_by (not updated_by).
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ,
    version                  BIGINT,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_by               VARCHAR(100),
    last_modified_by         VARCHAR(100)
);

CREATE INDEX idx_zone_transfer_visit
    ON zone_transfers (visit_id);

CREATE INDEX idx_zone_transfer_pending_visit
    ON zone_transfers (visit_id)
    WHERE status = 'PENDING_ACCEPT';

CREATE INDEX idx_zone_transfer_pending_to_zone
    ON zone_transfers (to_zone)
    WHERE status = 'PENDING_ACCEPT';

CREATE INDEX idx_zone_transfer_active_created
    ON zone_transfers (created_at DESC)
    WHERE is_active = TRUE;
