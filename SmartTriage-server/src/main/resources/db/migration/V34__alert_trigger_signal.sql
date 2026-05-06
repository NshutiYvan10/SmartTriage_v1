-- V34 — Round 4a: link RETRIAGE_REQUIRED alerts back to the
-- clinical-sign event that produced them.
--
-- Round 3 carried the trigger event id on triage_records (V33) for the
-- AutoBump path, but the Suggest path created a ClinicalAlert without
-- any audit link. Round 4a needs that link so the AlertsTab on the
-- frontend can click-through to a manual triage form pre-flagged with
-- the worsening sign — without that link the click-through has to
-- guess which sign caused the alert.
--
-- Two nullable columns:
--   triggering_sign_event_id — FK to clinical_sign_events(id), populated
--     for both auto-bump and suggestion alerts going forward.
--   triggering_sign_code — denormalised flat string (e.g.
--     "MSAT_VU_CHEST_PAIN"), populated alongside the FK. Stored even
--     though it duplicates a value you can join through to, because
--     the frontend's click-handler needs it on the alert object
--     directly without a follow-up fetch.
--
-- No backfill: existing alerts (TEWS_ESCALATION, TEWS_CRITICAL etc.)
-- have no clinical-sign trigger by construction; the new RETRIAGE_REQUIRED
-- rows from this point onwards will populate both fields.
--
-- Partial index keeps the FK lookup fast without storing entries for
-- the long tail of NULL-valued non-retriage alerts.

ALTER TABLE clinical_alerts
    ADD COLUMN triggering_sign_event_id UUID NULL
        REFERENCES clinical_sign_events(id),
    ADD COLUMN triggering_sign_code VARCHAR(60) NULL;

CREATE INDEX idx_alert_triggering_sign
    ON clinical_alerts (triggering_sign_event_id)
    WHERE triggering_sign_event_id IS NOT NULL;
