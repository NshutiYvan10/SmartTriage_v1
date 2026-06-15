-- V70 — persist the paramedic's field-triage INPUT so a re-open is faithful.
--
-- Phase-2 (V69) stored only the engine OUTPUT (category, TEWS, decision
-- path) plus the field vitals. On re-opening an en-route run the
-- discriminator flags and TEWS components (mobility/AVPU/trauma) were not
-- recoverable, so a re-compute from a blank form could silently produce a
-- LOWER acuity than the original — a clinically dangerous silent downgrade.
--
-- We now persist the full FieldTriageRequest as JSON so the form rehydrates
-- exactly what the paramedic last assessed. A server-side guard
-- (acknowledgeDowngrade) additionally blocks any silent acuity downgrade on
-- re-compute. Additive, nullable.

ALTER TABLE ems_runs
    ADD COLUMN field_triage_input TEXT;

COMMENT ON COLUMN ems_runs.field_triage_input IS
    'JSON of the last FieldTriageRequest (vitals + TEWS components + discriminators) so a re-open rehydrates the exact assessment; guards against silent re-compute downgrades.';
