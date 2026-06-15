-- V69 — Paramedic / EMS workflow Phase 2
--
-- Two additions to the pre-hospital run:
--
--  1. ENGINE-COMPUTED FIELD TRIAGE. Phase 1 stored the paramedic's
--     field triage as a free category string (field_triage_category) the
--     paramedic picked by hand. That is not the same call the ED makes.
--     We now run the SAME Rwanda TEWS + decision engine (adult or KFH
--     peds) over the field vitals + emergency/very-urgent/urgent
--     discriminators, so the field category is computed identically to
--     the in-hospital triage and can be trusted by the receiving team.
--     We persist the computed TEWS and the engine's decision-path audit
--     string alongside the existing category, plus which form (adult vs
--     child) was used.
--
--  2. "LIGHTS" / PRIORITY-TRANSPORT FLAG. A blue-light run signals an
--     incoming emergency transport. The flag drives urgent pre-arrival
--     alerting (audible alarm + RESUS-zone routing) on the hospital side.
--
-- All columns are additive and nullable (or defaulted), so existing
-- Phase 1 runs remain valid.

ALTER TABLE ems_runs
    ADD COLUMN field_tews_score          INT,
    ADD COLUMN field_triage_decision_path TEXT,
    ADD COLUMN field_triage_is_child      BOOLEAN,
    ADD COLUMN lights_active              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lights_activated_at        TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN ems_runs.field_tews_score IS
    'TEWS computed by the shared triage engine from field vitals (0-18). NULL until field triage is run.';
COMMENT ON COLUMN ems_runs.field_triage_decision_path IS
    'Audit string from the Rwanda/KFH decision engine explaining how the field category was reached.';
COMMENT ON COLUMN ems_runs.field_triage_is_child IS
    'TRUE when the KFH pediatric form/engine was used (patient <13y), FALSE for the adult form, NULL before field triage.';
COMMENT ON COLUMN ems_runs.lights_active IS
    'Blue-light priority transport. Drives urgent (audible) pre-arrival alerting on the hospital side.';
